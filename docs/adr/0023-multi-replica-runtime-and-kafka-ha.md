# ADR-0023: Multi-Replica Runtime and Kafka HA Strategy

## Status

Accepted

## Context

Through Phases 1 to 13, HyperScale Commerce built and verified robust application-level distributed mechanisms:
- Transactional outbox relay with at-least-once delivery (`app`).
- Asynchronous CQRS projections with monotonic version guards (`order-query`).
- Choreographed sagas and inventory failure compensation.
- Bounded retries, dead-letter queues (DLQs), and administrative replay.
- Process-local Caffeine caches and event-driven invalidation.
- Database-level concurrency controls (`FOR UPDATE SKIP LOCKED`) for outbox polling.
- Process-local client rate limiting.

However, the verified physical topology still runs:
1. Exactly one instance of `app` (port 8080).
2. Exactly one instance of `order-query` (port 8081).
3. Exactly one Kafka broker running KRaft with topic replication factor 1.
4. Exactly one PostgreSQL 16 primary instance.

This leaves critical architectural and operational gaps:
1. **Stateless Tier Single Points of Failure:** Loss or restart of an `app` or `order-query` container results in total unavailability for that service's APIs until the container recovers.
2. **Message Broker Single Point of Failure:** Loss of the single Kafka broker prevents new outbox event publication and halts consumer progress; broker storage failure risks unrecoverable event loss for acknowledged events.
3. **Unqualified Multi-Container Worker Coordination:** Database primitives (`SKIP LOCKED`) and consumer group assignments have not been verified with multiple physical container replicas actively polling, rebalancing, and recovering under sustained traffic and abrupt failure.
4. **Rate-Limit Quota Multiplication:** The Phase 13 rate limiter stores counters in-memory per JVM process. Ordinary round-robin routing across $N$ application replicas would allow a client to consume $N \times$ its intended quota unless admission control is centralized or managed at ingress.
5. **Overbroad Availability Claims:** Earlier phase verifications proved application mechanisms under simulated faults, but cannot support an infrastructure high-availability claim without proving multi-replica failover, broker leader election, and ingress health routing.

We must define the minimal, reproducible, and verifiable high-availability architecture for Phase 14 while maintaining the repository's strict scope and technology boundaries.

---

## Decision

We adopt a dedicated Multi-Replica Runtime and Kafka High Availability Strategy for Phase 14, comprising the following architectural pillars:

```text
                         k6 / external client
                                  |
                   health-aware HAProxy (:8080, :8081)
                   /                               \
       app replica pool                   order-query replica pool
       (app-1, app-2, ...)               (order-query-1, order-query-2, ...)
          |        \                               /        |
          |         +-------------+---------------+         |
          |                       |                         |
          +-------------> Kafka KRaft Quorum <--------------+
                         (broker-1, broker-2, broker-3)
                         RF=3, min.insync.replicas=2
                                  |
                            PostgreSQL 16
                         single primary instance
                     (documented negative control)
```

### 1. Health-Aware Ingress & Stateless Multi-Replica Runtime
- **HAProxy Ingress:** Introduce a digest-pinned HAProxy container (`haproxy:2.9-alpine@sha256:...`) as the single ingress load balancer for the Phase 14 HA profile.
- **Service Pools:** Run at least two replicas of `app` (`app-1`, `app-2`) and two replicas of `order-query` (`order-query-1`, `order-query-2`). Replicas bind to internal container ports without fixed host-port requirements.
- **Health Checks & Dynamic Routing:** HAProxy actively polls Actuator health probes (`/actuator/health/readiness`) on backends. Unhealthy or draining backends are removed from routing within 5 seconds (2 consecutive failed checks at 2s intervals).
- **Graceful Drain:** Maintain Spring Boot graceful shutdown (`server.shutdown: graceful` with 30s timeout). On `SIGTERM`, replicas fail readiness, allowing HAProxy to divert new traffic while the replica completes active in-flight requests.
- **Instance Identity:** Every application replica generates a unique, non-secret instance identifier (`instanceId` from hostname/UUID) recorded in logs and metrics to trace request and consumer ownership.
- **Header Sanitization:** HAProxy strips or overwrites client-supplied `X-Forwarded-For` and client-identifying headers, forwarding only the verified client IP to backend services.

### 2. Three-Node Kafka KRaft High-Availability Cluster
- **KRaft Quorum:** Deploy three deterministic Kafka nodes (`broker-1`, `broker-2`, `broker-3`) running in combined broker/controller mode with fixed node IDs (1, 2, 3) and a unified cluster ID.
- **Topic Durability Configuration:**
  - Business topics (`order-placed`, `order-cancelled`, `inventory-failed`) and DLQ topics use:
    - **Replication Factor:** $RF = 3$ (all brokers maintain a full replica).
    - **Min In-Sync Replicas:** `min.insync.replicas = 2`.
    - **Partitions:** $\ge 3$ partitions for scalable consumer concurrency.
    - **Unclean Leader Election:** Explicitly disabled (`unclean.leader.election.enable = false`).
- **Producer Durability Contract:**
  - Outbox relay producers use `acks=all` (or `-1`), `enable.idempotence=true`, `retries=Integer.MAX_VALUE`, and `max.in.flight.requests.per.connection=5`.
  - Messages for an aggregate are keyed by aggregate root ID (`orderId`), preserving strict per-order partition ordering.
- **Single-Broker Tolerance:** If any one broker fails or is killed:
  - KRaft controller quorum (2 of 3) remains active.
  - Partition leaders on the lost broker fail over to an in-sync follower within 5 seconds.
  - Producers continue acknowledging writes because 2 in-sync replicas satisfy `min.insync.replicas=2`.
  - Zero acknowledged records are lost.

### 3. Topology-Wide Rate-Limiting Admission Ownership
- **Ingress Quota Enforcement:** Topology-wide client rate limiting (500 req/min per client IP) is enforced at the single HAProxy ingress layer using HAProxy stick-tables.
- **Application-Local Defense in Depth:** The Phase 13 per-instance application filter is retained with identical or slightly higher thresholds as a secondary bulkhead against direct internal traffic.
- **Standardized Rejection:** HAProxy and application filters emit `HTTP 429 Too Many Requests` with a `Retry-After: <seconds>` header.
- **No Secret/Identity Leaks:** Metrics record rate-limit rejections as bounded counters without embedding dynamic client IPs or API keys as metric labels.

### 4. Concurrent Outbox & Consumer Group Coordination
- **Disjoint Outbox Claims:** Multiple `app` instances independently poll `order.outbox_events` using `SELECT ... FOR UPDATE SKIP LOCKED`, claiming non-overlapping batches without lock contention.
- **Interrupted Batch Recovery:** If an `app` worker dies mid-batch, its uncommitted database transaction rolls back, releasing rows for surviving workers; if rows were marked locked, the abandoned-lock timeout automatically unlocks them.
- **Consumer Rebalances:** Multiple `order-query` instances in consumer group `order-query-group` share topic partitions. During replica loss or addition, Kafka rebalances partitions. Idempotent handlers and aggregate version guards (`version <= current_version` rejection) guarantee that out-of-order or duplicate deliveries during rebalance never corrupt the read model.

### 5. Failure Domains, Invariants, and Prohibited Claims
- **Tested & Protected Domains:**
  - Loss of 1 `app` replica: Continuous write availability via surviving replica(s) after HAProxy convergence.
  - Loss of 1 `order-query` replica: Continuous query availability via surviving replica(s) after HAProxy convergence.
  - Loss of 1 Kafka broker: Uninterrupted producer writes, partition leader failover, zero lost acknowledged messages.
- **Negative Controls (Documented Single Points of Failure):**
  - **Kafka Quorum Loss (2 brokers down):** Producers fail with `NotEnoughReplicasException`; outbox relay buffers events durably in PostgreSQL; write API (`POST /orders`) remains functional; events drain automatically upon cluster recovery.
  - **PostgreSQL Primary Loss:** The database is an unreplicated single primary in Phase 14. Database loss halts write operations and fails readiness probes. No failover is claimed; tests verify honest degradation and zero silent corruption.
  - **Ingress (HAProxy) Loss:** The ingress is a single instance in this qualification phase. Ingress loss halts public traffic. Multi-zone or VRRP ingress HA is explicitly deferred.
- **Prohibited Claims:** The platform MUST NOT claim production-wide 99.9% availability, Kubernetes-style self-healing, multi-region failover, or database high availability in Phase 14.

---

## Alternatives Considered

1. **Direct Ports / Client-Side Balancing (No Ingress Proxy):**
   - *Rejected:* Exposes internal replica ports (e.g. 8080, 8082, 8083) to clients; forces external clients to implement health checking and topology tracking; fails to provide centralized rate-limiting and connection draining.
2. **Immediate Kubernetes / Nomad / Service Mesh Adoption:**
   - *Rejected:* Violates BootCamp precedence rules (AGENTS.md, Phase 14 constraints). Introduces massive operational abstractions (etcd, CNI, ingress controllers, sidecars) before container and broker HA fundamentals are empirically measured.
3. **Distributed In-Memory Store (Redis) for Rate Limiting:**
   - *Rejected:* Introducing Redis adds another stateful cluster dependency solely for rate-limit counters. Enforcing the quota at the single HAProxy ingress solves the multi-replica quota multiplication problem with zero additional stateful infrastructure.
4. **Kafka with Replication Factor 2 ($RF=2, \text{min.isr}=1$):**
   - *Rejected:* If one broker fails in a 2-broker setup, `min.isr=1` allows writes to a single remaining replica with no redundancy, risking data loss on subsequent failure and preventing clean quorum decisions. $RF=3, \text{min.isr}=2$ is the industry-standard minimum for resilient consensus.
5. **Bundling PostgreSQL HA (Patroni / Streaming Replicas) into Phase 14:**
   - *Rejected:* Violates incremental architecture principles. Database replication, split-brain fencing, and failover mechanics are large and complex enough to require their own dedicated phase and ADR.

---

## Consequences

### Positive
- **High Availability for Stateless Tier:** Application instances can be terminated, updated, or restarted with zero client-observed downtime after health check convergence.
- **Message Broker Durability:** The messaging backbone survives individual node crashes without data loss or pipeline stalls.
- **Fair Admission Control:** Clients cannot circumvent rate limits by spreading requests across different backend replicas.
- **Production-Grade Infrastructure Foundation:** Establishes the multi-instance baseline required for subsequent cloud and container orchestration deployments.

### Negative / Tradeoffs
- **Resource Footprint:** Running 3 Kafka nodes, 2 `app` instances, 2 `order-query` instances, HAProxy, and PostgreSQL requires at least 4 CPU cores and 8GB RAM allocated to Docker.
- **Ingress Hop Latency:** HAProxy adds a minor network hop (target: $< 5\text{ms}$ p95 overhead).
- **Ingress Stick-Table Ephemerality:** Rate-limit counters are held in HAProxy memory; an ingress restart resets client quotas (documented acceptable limitation for single-ingress testing).

---

## Failure Modes & Mitigations

| Failure Mode | Impact | Mitigation / Recovery |
|---|---|---|
| Single `app` replica killed (`SIGKILL`) | In-flight requests on that container fail (502/TCP reset); subsequent requests routed to surviving replica | HAProxy health check removes dead backend within $\le 5\text{s}$; client retries succeed on surviving replica; outbox abandoned locks clear automatically. |
| Single `order-query` replica killed | Queries routed to surviving replica; Kafka partition assignment loses 1 consumer | HAProxy marks backend down; Kafka consumer group rebalances partitions to surviving instance; projections catch up from last committed offset. |
| Single Kafka broker terminated | Leader partitions on that broker become temporarily unavailable during leader election | KRaft elects in-sync follower as leader within $\le 5\text{s}$; producer retries succeed; outbox relay resumes delivery; consumer group resumes consumption. |
| Two Kafka brokers terminated (Quorum loss) | Topic writes fail `NotEnoughReplicasException`; read projections pause | Negative control: `POST /orders` continues accepting orders by storing in PostgreSQL outbox; outbox relay retries with backoff; upon broker recovery, outbox drains automatically with 0 lost orders. |
| PostgreSQL primary terminated | Database writes and reads fail; readiness probes fail | Negative control: Both services fail readiness probes; HAProxy returns 503 Service Unavailable; no uncommitted data is acknowledged. |
| Ingress rate limit saturated | Abusive client receives `429 Too Many Requests` | HAProxy stick-table tracks client IP and rejects excess requests without loading backend application containers. |

---

## Workload, Environmental & Verification Contract

- **Steady-State Critical Latency:** All 5 Critical APIs (`GET /catalog/products/{id}`, `GET /catalog/products`, `POST /orders`, `GET /orders/{id}`, `GET /orders`) must maintain **p95 < 200ms** under the nominal qualification workload.
- **Ingress Overhead:** HAProxy latency overhead relative to direct container access must be **p95 < 5ms**.
- **Single Replica Failover Convergence:** Critical API p95 must recover below 200ms within **30 seconds** of replica loss.
- **Single Broker Failover & Drain:** Outbox publication and consumer lag must return to steady-state baseline within **60 seconds** of broker recovery.
- **Zero Data Loss:** 100% data reconciliation across PostgreSQL `order.outbox_events`, `inventory.inventory_reservations`, and `order_query.orders` with 0 DLQ messages after every test run.
- **Minimum Environment Specification:** 4 CPU cores, 8 GB RAM allocated to Docker Engine, 20 GB free disk space.
