# Architecture — HyperScale Commerce

## Current Stage

Phase 18 — Kotlin/JVM Engineering Maturity & Concurrency Safety (**IN PROGRESS;
REMEDIATION AFTER FAILED REVIEW**). Phase 17 remains the latest completed and
verified architecture baseline.


## Current Verified Architecture

The latest completed and evidenced baseline is Phase 17: two horizontally scalable services and replicated HAProxy ingress on a six-node local `kind` cluster, communicating asynchronously through a 3-broker Kafka KRaft cluster (RF=3, `min.insync.replicas=2`) and persisting to a 3-node PostgreSQL 16 streaming-replication cluster governed by Patroni and a 3-member `etcd` DCS cluster (`synchronous_standby_names = 'ANY 1 (...)'`). Each service uses a bounded Caffeine L1 cache backed by authenticated Redis L2 storage, with Kafka-broadcast invalidation across pods. Transaction-aware dual Hikari pools route writes to the current Patroni primary and eligible read-only traffic to strict secondaries; unhealthy or lagging replicas are fenced back to the primary. Continuous physical basebackups and WAL archiving provide a Point-In-Time Recovery (PITR) plane.

```text
                  external load & test plane
             +-----------------------------------+
             | k6 scenarios + result summaries  |
             | resource/metric snapshot scripts |
             | HA chaos failure test harness    |
             +----------------+------------------+
                              |
                     [Public Ingress Ports]
                     :8080 (App)  :8081 (Query)
                              |
                              v
             +-----------------------------------+
             |   Replicated HAProxy Ingress (2)  |
             |   - Stick-Table Rate Limiting     |
             |   - Active Readiness Healthcheck  |
             |   - Forwarded-For Sanitization    |
             |   - Security Path Protection      |
             +----------------+------------------+
                              |
               +--------------+--------------+
               |                             |
               v                             v
      [app pool (:8080)]           [order-query pool (:8081)]
      +-----------------+          +-------------------------+
      | 3..8 replicas   |          |      3..8 replicas      |
      | Caffeine L1     |          |      Caffeine L1        |
      +--------+--------+          +------------+------------+
          |        +----------+----------+            |
          |                   v                       |
          |        +-----------------------+          |
          |        | Redis L2 StatefulSet  |          |
          |        | auth + AOF + PVC      |          |
          |        +-----------------------+          |
          |                                            |
          +---+------------------------------+---------+
              |                                ^
              v                                |
     order.outbox_events                       |
              |                                |
              +-------> [3-Broker Kafka] ------+
                        (RF=3, min.isr=2)
                        kafka-1, kafka-2, kafka-3
                               |
                        Inventory Consumer
                               |
                               v
             +-----------------------------------+
             |       3-Member etcd Cluster       |
             |   etcd-1    etcd-2    etcd-3      |
             +-----------------+-----------------+
                               |
                      (etcd3 DCS Leases)
                               |
        +----------------------+----------------------+
        |                      |                      |
        v                      v                      v
+---------------+      +---------------+      +---------------+
|  postgres-1   |      |  postgres-2   |      |  postgres-3   |
| Patroni (8008)|<====>| Patroni (8009)|<====>| Patroni (8010)|
| PostgreSQL:5432| Sync |PostgreSQL:5433| Async|PostgreSQL:5434|
|   (Primary)   | Rep  | (Sync Standby)| Rep  |   (Standby)   |
+---------------+      +---------------+      +---------------+
```

## Deployables

| Deployable | Module | Port | Owned schemas | Responsibilities |
|---|---|---|---|---|
| `app` | `app` | 8080 | `catalog`, `order`, `inventory` | Catalog reads, Order commands, transactional outbox relay, Inventory consumer, saga compensation, L1/L2 caching, per-instance rate limiting |
| `order-query` | `order-query` | 8081 | `order_query` | `OrderPlaced` and `OrderCancelled` projections, monotonic version guard, read APIs, L1/L2 caching, DLQ replay API |
| contracts | `contracts` | — | — | Shared versioned event contracts |
| load-generator (test only) | `performance` | — | — | External k6 load harness driving HTTP ports 8080/8081 |
| chaos harness (test only) | `performance/chaos` | 8474 | — | Toxiproxy network latency, packet slicing, and connection cut injection |

## Communication

- Cross-service communication is exclusively Kafka events, including
  `order-placed`, inventory failure, and `order-cancelled` flows.
- No synchronous inter-service calls (REST/gRPC) across deployables.
- The transactional outbox in `app` guarantees durable event publication.
- Dedicated Kafka invalidation topics fan out evictions to independent pod
  consumer groups so every local L1 is invalidated without synchronous calls.
- `order-query` consumes with dedicated consumer groups and projects into
  `order_query.order_read_model` with aggregate-version guards.
- Poison events use bounded retries and DLQs; the administrative replay path
  enforces a bounded redrive count.
- Distributed tracing (Micrometer Tracing + Brave) and correlation IDs flow across
  HTTP requests and Kafka record headers without requiring external collector infrastructure.

## Data Ownership

Each deployable owns its persistence:

- `app` owns the `catalog`, `order`, and `inventory` schemas.
- `order-query` owns the `order_query` schema (read model only).
- Per-service Flyway migrations and jOOQ codegen with separate history tables.
- Cross-schema queries in application code are strictly forbidden. Test-only
  reconciliation scripts query owned schemas independently for consistency verification.

## Module Boundaries

```text
app ──────────> contracts
order-query ──> contracts
```

`app` and `order-query` must not depend on each other. ArchUnit enforces
package-level dependency rules within each module.

## Monolith Internal Structure

```text
com.hyperscale.commerce
  modules
    catalog
    order
    inventory
    shared
```

Each bounded context owns its business rules and persistence; dependency
direction follows `api -> application -> domain` with infrastructure
implementing domain interfaces.

## External Load & Chaos Planes

- **Isolation:** k6 runs as an external container (`grafana/k6:0.57.0@sha256:...`) under a test-only Compose profile.
- **Toxiproxy Fault Injection:** Toxiproxy container (`ghcr.io/shopify/toxiproxy:2.11.0`) intercepts all database and Kafka traffic for deterministic chaos simulation.
- **Black-Box Access:** Drives public HTTP ports on `app` (8080) and `order-query` (8081).
- **Zero Runtime Contamination:** No test libraries, test controllers, or load agents exist inside `app` or `order-query`.

## Verified Capabilities Through Phase 17

- Deterministic 3-node PostgreSQL 16 streaming replication cluster managed by Patroni 3.x with a 3-member `etcd` DCS cluster (`loop_wait=10s`, `ttl=30s`).
- Strict synchronous replication (`synchronous_mode: true`, `synchronous_commit: on`, `synchronous_standby_names = 'ANY 1 (...)'`), guaranteeing zero data loss ($\text{RPO} = 0$) for all acknowledged commits.
- Primary failover $\text{RTO} = 18.0\text{s}$ ($\le 30\text{s}$ target) under 5x load with automatic standby promotion and old primary rewind/rejoin.
- Zero dual-primary / split-brain states across network isolation and etcd quorum loss failure scenarios.
- Direct multi-host JDBC routing (`targetServerType=primary`) with HikariCP pool revalidation avoiding proxy single points of failure.
- Physical basebackups with continuous WAL archiving and verified Point-In-Time Recovery (PITR) with 100% sentinel transaction precision.
- Deterministic 3-broker Kafka KRaft cluster with replication factor 3, `min.insync.replicas=2`, and `acks=all` producer durability.
- Horizontally scalable `app` and `order-query` Deployments (3..8 replicas) behind two HAProxy ingress pods with peer-synchronized sliding-window rate limiting.
- Declarative Helm packaging on a six-node local `kind` cluster, including probes, rolling-update controls, HPAs, PDBs, NetworkPolicies, RBAC, and restricted non-root security contexts.
- Bounded Caffeine L1 caches backed by authenticated Redis L2 storage, with
  fail-open database fallback and event-driven cross-pod invalidation.
- Transaction-aware read/write routing through separate Hikari pools: writes
  and Flyway remain primary-directed, while eligible read-only transactions use
  strict PostgreSQL secondaries.
- Fail-closed replica health and replay-lag fencing at 100 ms, with automatic
  primary fallback when no secondary is safe.
- Phase 17 qualification at 5,000 peak VUs and 2,105.58 RPS: catalog p95 1.768
  ms, normal Order Query p95 6.216 ms, order-create p95 10.427 ms, normal
  primary CPU 14.514%, zero HTTP failures during injected Redis loss and replica
  lag, and 5,655/5,655 reconciliation.
- 100% cross-schema SQL data reconciliation post-recovery.

## Current Topology Limits

- Both HAProxy replicas and all six `kind` nodes still reside on one physical Docker host; physical host, multi-AZ, and cross-region failure domains are not proved.
- Persistent volumes use local `kind` worker storage rather than independently durable cloud or SAN failure domains.
- Redis remains a non-authoritative performance layer. Its StatefulSet and PVC
  are local-cluster facilities, and Redis loss intentionally falls back to
  PostgreSQL.
- Replica lag fencing preserves correctness by returning reads to the primary;
  primary load can therefore rise during prolonged replica degradation.
- The current evidence establishes bounded local high availability and does not establish a production-wide 99.9% availability claim.

## References

- ADR-0010 — Extract the Order query side as the first service
- ADR-0011 — Monorepo module and per-service data-ownership model
- ADR-0012 — Resilience Strategy for Distributed Communication
- ADR-0013 — Observability Strategy for the Two-Service Platform
- ADR-0014 — Load-Test Strategy and Qualification Model
- ADR-0015 — Chaos Engineering, Network Fault Injection, and Distributed Failure Strategy
- ADR-0016 — Production Hardening, Security, Lifecycle Management, and Operational Alerting Strategy
- ADR-0017 — Distributed Saga and Compensating Transaction Strategy
- ADR-0018 — API Idempotency and Request Deduplication Strategy
- ADR-0019 — Event Schema Evolution and Versioning Strategy
- ADR-0020 — Adaptive Load Shedding and Rate Limiting
- ADR-0021 — Caching, Multi-Replica Scheduling, and Storage Lifecycle Strategy
- ADR-0022 — Distributed Stream Operations, DLQ Replay, and Out-of-Order Resilience
- ADR-0023 — Multi-Replica Runtime and Kafka HA Strategy
- ADR-0024 — PostgreSQL High Availability, Fencing, and Disaster Recovery Strategy
- ADR-0025 — Kubernetes Packaging, Stateful Quorums, Replicated Ingress, and Multi-Node Reliability
- ADR-0026 — Distributed Multi-Level Caching, Event-Driven Invalidation, and Read-Replica Routing
- Phase 14 Evidence Dossier — Multi-Replica Runtime, Ingress & Kafka HA
- Phase 15 Evidence Dossier — PostgreSQL High Availability, Fencing & Disaster Recovery
- Phase 15 plan — PostgreSQL High Availability, Fencing & Disaster Recovery
- Phase 16 plan — Local Kubernetes, Ingress, and Workload Orchestration
- Phase 16 Evidence Dossier — Local Kubernetes, Ingress, and Workload Orchestration
- Phase 17 plan — Distributed Multi-Level Caching & Read-Replica Scaling
- Phase 17 Evidence Dossier — Cache and Read-Replica Scaling
- Phase 17 Review — Passed
