# Phase 17 Plan: Distributed Multi-Level Caching & Read-Replica Scaling

**Phase:** Phase 17 — Distributed Multi-Level Caching & Read-Replica Scaling

**Status:** COMPLETED — PHASE REVIEW PASSED

**Date:** 2026-08-17

---

## 1. Phase Objective

Introduce distributed multi-level caching (pod-local Caffeine L1 + distributed Redis L2 near-cache) and PostgreSQL read-replica offloading (read/write transaction splitting with replication lag fencing) to scale the HyperScale Commerce platform towards 10,000+ concurrent users and sub-20ms read API p95 latencies while shielding the Patroni primary database from read traffic exhaustion.

---

## 2. Why This Phase Exists

In Phase 16, the platform successfully migrated to a multi-node Kubernetes cluster with autoscaling stateless services and high-availability stateful quorums. However, two major architectural bottlenecks limit scale:

1. **Unshared Pod-Local Caches (L1-Only):** In-memory Caffeine caches exist strictly per pod replica. In a dynamically scaling pool (3 to 8 pods), cache hit rates degrade due to cold caches on new replicas, causing repetitive database reads.
2. **Primary Database Read Contention:** All read queries currently route to the Patroni primary database (`targetServerType=primary`). As concurrent users scale to 10,000+, catalog and order query load directly competes for transactional locks and CPU cycles with order creation and outbox publishing.
3. **No Distributed Cache Invalidation:** Pods rely on fixed TTL expiration rather than event-driven cache invalidation across the cluster.

Phase 17 solves these bottlenecks by offloading reads to a distributed Redis L2 cache and Patroni read replicas while guaranteeing bounded staleness and monotonic read consistency. The phase review and qualification evidence verify these outcomes within the documented local Kubernetes failure domain.

---

## 3. Starting Architecture / State

- 6-node `kind` Kubernetes cluster (3 control-plane nodes, 3 worker nodes).
- Replicated HAProxy ingress (2 pods) with synchronized stick-table rate limiting (500 req/min).
- Stateless `app` (3+ replicas) and `order-query` (3+ replicas) Deployments with HPA v2.
- 3-broker KRaft Kafka cluster ($RF=3, \text{min.isr}=2$).
- 3-node Patroni PostgreSQL 16 cluster with strict synchronous standby (`ANY 1`, $\text{RPO}=0$).
- All queries route to the primary database.

---

## 4. Target Architecture / State

- **Multi-Level Near-Cache (L1 + L2):**
  - **L1 (In-Memory Caffeine):** Fast microsecond lookups for hottest keys per pod.
  - **L2 (Distributed Redis):** Shared millisecond cache cluster deployed on Kubernetes (`redis` StatefulSet/Cluster).
  - **Event-Driven Cache Invalidation:** Kafka topics (`catalog-cache-evict`, `inventory-cache-evict`, `order-cache-evict`) broadcast invalidations to all pod L1 caches and shared L2 state on data mutations.
- **Read-Write Splitting & Read-Replica Routing:**
  - Write transactions route exclusively to `postgres-ha-primary`.
  - Read-only transactions (`@Transactional(readOnly = true)`) route to strict-secondary multi-host JDBC URLs; the independent primary pool provides explicit lag/unavailability fallback.
  - **Replication-Lag Fencing:** Causal consistency tokens / max-lag checks ($< 100\text{ms}$) ensuring stale replicas are bypassed if WAL replay lags.
- **Measurable Scaling:** p95 read latency $< 15\text{ms}$ under 5,000+ concurrent virtual users without primary CPU spikes.

---

## 5. Problems This Phase Addresses

| Problem | Root Cause | Phase 17 Solution |
|---|---|---|
| Cold cache miss storms during HPA scale-out | In-memory cache isolated per pod | Shared Redis L2 cache provides warm data across all scaling pods |
| Primary database CPU/IO exhaustion during traffic spikes | Reads and writes share the single primary DB | Read-write splitting routes reads to Redis L2 and standby PostgreSQL replicas |
| Stale catalog/inventory reads across pods | Fixed TTL without cross-pod invalidation | Event-driven Kafka invalidation bus synchronizes all L1/L2 caches |
| Dirty reads from lagging replicas | Asynchronous replication lag | Causal consistency headers and replication-lag thresholds |

---

## 6. Architecture Changes

```text
                                 [HAProxy Ingress]
                                         |
                       +-----------------+-----------------+
                       |                                   |
                [App Service (3..8)]            [Order-Query Service (3..8)]
                 /         |        \             /         |         \
         (L1 Cache)   (L2 Cache)  (Kafka)   (L1 Cache)  (L2 Cache)   (Kafka)
          Caffeine       Redis     Events    Caffeine      Redis      Events
                       /       \                         /       \
             (Writes) /         \ (Reads)      (Writes) /         \ (Reads)
                     v           v                     v           v
            [Patroni Primary]  [Patroni Standbys]
               (Postgres)         (Postgres)
```

---

## 7. Technology Changes

### Introduced Technologies
- **Redis 7.2 (Alpine):** Deployed as a Kubernetes StatefulSet with persistent storage and Prometheus metrics exporter.
- **Spring Data Redis / Redisson:** Reactive/blocking Redis client integrated with Spring Cache abstraction.
- **Spring Routing DataSource (`AbstractRoutingDataSource`):** Dynamic connection routing between Primary and Replica HikariCP pools based on `@Transactional(readOnly)`.

### Forbidden Technologies (Deferred to Later Phases)
- Cloud-managed ElastiCache / MemoryDB
- Dynamic Cloud CSI provisioners
- Service mesh data plane filters
- Distributed XA Two-Phase Commit transactions

---

## 8. Non-Functional Requirements

- **Latency:** Catalog read p95 $< 10\text{ms}$, Order query p95 $< 20\text{ms}$.
- **Throughput:** Sustain 2,000+ RPS read traffic with $< 15\%$ primary database CPU utilization.
- **Resilience:** Redis cluster failure gracefully falls back to direct database reads without dropping requests (fail-open cache design).
- **Consistency:** Max allowable read-replica staleness $\le 100\text{ms}$; immediate read-your-writes consistency for write-originating sessions.

---

## 9. Performance & Reliability Expectations

| Dimension | Target | Failure / Degraded Mode |
|---|---|---|
| **L1 Cache Hit Latency** | $< 1\text{ms}$ | Falls back to L2 |
| **L2 Redis Hit Latency** | $< 5\text{ms}$ | Falls back to Read Replica |
| **Read-Replica Query Latency** | $< 20\text{ms}$ | Falls back to Primary if replica lag $> 100\text{ms}$ |
| **Cache Invalidation Latency** | $< 50\text{ms}$ end-to-end | Enforced via Kafka pub/sub broadcast |
| **Redis Node Loss** | 0 dropped requests | Redis failover or transparent fallback to DB |

---

## 10. Observability Requirements

- Metrics:
  - `hyperscale_cache_gets_total{level="L1|L2", result="hit|miss"}`
  - `hyperscale_cache_evictions_total{reason="event|mutation|ttl|lru"}`
  - `datasource_connections_active{pool="primary|replica"}`
  - `postgres_replication_lag_seconds{replica="..."}`
- Alert Rules:
  - `RedisNodeDown` (Critical)
  - `PostgresReplicationLagHigh` (Warning: lag $> 1\text{s}$)
  - `L2CacheMissRateSpike` (Warning: miss rate $> 40\%$)

---

## 11. Security Considerations

- Redis deployed in `hyperscale` namespace with `requirepass` authentication stored in Kubernetes Secrets.
- NetworkPolicies restricting Redis port 6379 access exclusively to `app` and `order-query` pods.
- Non-root security contexts (`runAsNonRoot: true`) on Redis containers.

---

## 12. Data Considerations

- Cache keys prefixed with domain namespaces (`catalog:v1:sku:...`, `inventory:v1:sku:...`).
- Cache serialization uses strict JSON schemas (Jackson) with backwards-compatible schema versioning.
- Null caching with short TTL (30s) to prevent cache penetration / stampede on non-existent items.

---

## 13. Explicitly Out-of-Scope Capabilities

- Distributed Redis multi-region active-active replication.
- Cloud-managed ElastiCache.
- Complex write-through / write-behind cache tiers (cache is read-aside with event invalidation).
- Physical multi-cloud Kubernetes networking.

---

## 14. Dependencies on Previous Phase

- Relies on Phase 16 6-node `kind` Kubernetes cluster, Helm packaging foundation, and Patroni PostgreSQL streaming replication cluster with synchronous and asynchronous standbys.

---

## 15. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| **Cache Stampede on Key Expiration** | Probabilistic early expiration (XFetch algorithm) and distributed mutex locking on cache misses |
| **Stale Reads from Lagging Replicas** | Automatic replication-lag monitoring and routing fallback to primary if lag $> 100\text{ms}$ |
| **Redis Crash Bringing Down App** | Fail-open cache interceptor catching Redis exceptions and falling back to PostgreSQL |

---

## 16. ADRs Required

- **ADR-0026:** Distributed Multi-Level Caching (L1/L2 Near-Cache), Event-Driven Invalidation, and Read-Write Replica Routing Strategy.

---

## 17. Ordered Implementation Tasks

### P17-01 — Architecture Decision Record ADR-0026 & Plan Approval (COMPLETED)
- **Objective:** Finalize ADR-0026 defining multi-level caching, invalidation topics, read/write splitting, and replication lag fencing.
- **Dependencies:** Phase 16 complete.

### P17-02 — Redis Distributed L2 Cache Packaging on Kubernetes (COMPLETED)
- **Objective:** Package Redis 7.2 as a resilient Kubernetes StatefulSet with Secrets, PVCs, headless/client Services, and NetworkPolicies in Helm.
- **Dependencies:** P17-01.

### P17-03 — Multi-Level Near-Cache Implementation (L1 Caffeine + L2 Redis) (COMPLETED)
- **Objective:** Implement unified L1/L2 caching abstraction with fail-open resilience in `app` and `order-query`.
- **Dependencies:** P17-02.

### P17-04 — Event-Driven Cache Invalidation Bus (COMPLETED)
- **Objective:** Implement Kafka-driven cache invalidation producers and consumers to broadcast instant evictions on entity modifications.
- **Dependencies:** P17-03.

### P17-05 — PostgreSQL Read/Write Splitting & Dynamic DataSource Routing (COMPLETED)
- **Objective:** Implement `AbstractRoutingDataSource` in Spring Boot routing `@Transactional(readOnly = true)` to Patroni replicas with lag fencing.
- **Dependencies:** P17-01.

### P17-06 — Cache & Replica Observability, Alerts & Runbooks (COMPLETED)
- **Objective:** Instrument L1/L2 hit/miss metrics, replication lag gauges, Prometheus alert rules, and operator runbooks.
- **Dependencies:** P17-03 through P17-05.

### P17-07 — High-Concurrency Performance, Fault Injection & Scaling Qualification (COMPLETED)
- **Objective:** Execute k6 load tests (5,000+ VUs), verify primary DB offloading, inject Redis crashes and replica lag, and reconcile 100% data.
- **Dependencies:** P17-03 through P17-06.

### P17-08 — Phase 17 Evidence Dossier and Phase Review (COMPLETED)
- **Objective:** Consolidate empirical evidence, update living architecture documentation, and execute formal phase review.
- **Dependencies:** P17-01 through P17-07.


---

## 18. Task Acceptance-Criteria Matrix

| Task | Required Acceptance Outcome |
|---|---|
| **P17-01** | ADR-0026 authored, approved, and linked; Phase 17 plan approved |
| **P17-02** | Redis StatefulSet deploys cleanly with auth, PVCs, PDBs, and NetworkPolicies |
| **P17-03** | L1/L2 near-cache delivers microsecond/millisecond hits with fail-open fallback |
| **P17-04** | Data mutations trigger Kafka invalidations evicting keys across all pods $< 50\text{ms}$ |
| **P17-05** | Read queries route to replicas; writes route to primary; lag $> 100\text{ms}$ triggers primary fallback |
| **P17-06** | Cache hit rates and replica lag observable; alerts and runbooks published |
| **P17-07** | High-concurrency load meets p95 $< 15\text{ms}$; primary CPU $< 15\%$; 100% data reconciliation |
| **P17-08** | Evidence dossier published; architecture docs updated; phase review passes |

---

## 19. Phase Exit Criteria

- Review result: **PASSED**. See [Phase 17 Review — Passed](evidence/p17-phase-review.md).

- [x] All 8 implementation tasks completed and verified.
- [x] ADR-0026 accepted.
- [x] L1/L2 near-cache and read-replica routing operational on Kubernetes.
- [x] Catalog read p95 $< 10\text{ms}$ and primary DB offloading proved under load.
- [x] 100% data reconciliation maintained under the Phase 17 workload.
- [x] Phase 17 Review PASSED.
