# ADR-0026: Distributed Multi-Level Caching, Event-Driven Invalidation, and Read-Replica Routing

- **Status:** Accepted
- **Date:** 2026-08-17
- **Deciders:** Architecture Review Board, Antigravity AI Engineering Harness
- **Phase:** Phase 17 — Distributed Multi-Level Caching & Read-Replica Scaling
- **Consulted:** AGENTS.md, docs/constitution.md, Phase 16 Evidence Dossier, Phase 17 Plan

---

## 1. Context and Problem Statement

In Phase 16, HyperScale Commerce was packaged as an autoscaling multi-replica platform on Kubernetes (`kind` cluster with 3 control-plane and 3 worker nodes).

While write transactions are safely committed with $\text{RPO}=0$ strict synchronous replication, read workloads present scaling bottlenecks:
1. **Isolated In-Memory Caches (L1-Only):** Each pod maintains its own Caffeine cache. When HPA scales out replicas (from 3 to 8 pods), new pods suffer cold-cache misses, triggering database query spikes.
2. **Primary Database Contention:** Read queries (Catalog listings, Order Query lookups) route to the Patroni primary database (`targetServerType=primary`), competing for connection slots, CPU, and shared buffers against critical order creation and outbox write transactions.
3. **No Distributed Invalidation:** Cache entries rely solely on TTL expiration rather than event-driven invalidation upon data mutation.

We need an architecture that offloads read traffic to distributed caching tiers and standby read replicas while guaranteeing monotonic read consistency and sub-20ms p95 latencies under high concurrency.

---

## 2. Alternatives Considered

| Option | Strengths | Weaknesses | Decision |
|---|---|---|---|
| **Option 1: Primary Vertical Scaling & Read-through DB** | No new infrastructure or cache invalidation logic. | Limited by single primary CPU/connection limits; cannot scale to 10,000+ users. | **REJECTED** |
| **Option 2: Pure Distributed L2 Cache (Redis Only)** | Shared cache state across all pods; simple invalidation. | Every read requires a network round-trip ($\approx 2-5\text{ms}$), increasing network I/O and Redis CPU overhead. | **REJECTED** |
| **Option 3: Multi-Level Near-Cache (L1 Caffeine + L2 Redis) + Read/Write Splitting** | Microsecond hot-key lookups in L1; warm cache shared via L2; read-replica offloading; event-driven invalidation. | Requires multi-level cache synchronization and replication-lag fencing. | **ACCEPTED** |

---

## 3. Decision Outcome

Adopt a **Multi-Level Near-Cache Architecture** and **Dynamic PostgreSQL Read/Write Splitting**:

### 3.1 Multi-Level Near-Cache Architecture
- **L1 (Pod-Local Caffeine Cache):** Ultra-low-latency in-memory cache for hottest entries ($< 1\text{ms}$, short TTL $60\text{s}$, maximum 10,000 keys per pod).
- **L2 (Distributed Redis Cache):** Shared Redis 7.2 cluster packaged on Kubernetes as a StatefulSet with persistent storage and Prometheus exporter ($< 5\text{ms}$, TTL $10\text{m}$).
- **Fail-Open Policy:** If Redis becomes unreachable, the cache client transparently catches errors and falls back directly to the database without dropping client requests.

### 3.2 Event-Driven Cache Invalidation Bus
- When an entity is modified (e.g., catalog product price change, inventory allocation, or order projection update), the service produces an invalidation event to dedicated Kafka broadcast topics (`catalog-cache-evict`, `inventory-cache-evict`, `order-cache-evict`).
- All active application pods consume from a unique broadcast consumer group and instantly evict the key from their local L1 Caffeine cache and the shared Redis L2 cache ($< 50\text{ms}$ end-to-end propagation).

### 3.3 PostgreSQL Read/Write Splitting & Replica Routing
- **Spring `AbstractRoutingDataSource`:**
  - Write transactions (`@Transactional(readOnly = false)`) route to the `PRIMARY` HikariCP pool (`postgres-ha-primary` / `targetServerType=primary`).
  - Read transactions (`@Transactional(readOnly = true)`) route to the `REPLICA` HikariCP pool using strict `targetServerType=secondary`; lag or pool failure causes the routing layer to select its independent primary pool.
- **Replication-Lag Fencing:**
  - The application periodically monitors Patroni standby replay lag via `pg_stat_replication` or Patroni REST API (`/health`).
  - If replica lag exceeds $100\text{ms}$, read queries dynamically fall back to the primary database to prevent dirty or stale reads.

---

## 4. Consequences and Non-Claims

### Expected Positive Consequences
- Target sub-15ms p95 read latencies under 5,000+ concurrent virtual users.
- Target primary database CPU reduction of $> 75\%$ during read-heavy spikes.
- Shared L2 state intended to reduce cold-start cache miss storms during HPA replica scale-out.

These are decision goals, not verified outcomes. Phase 17 must not claim them
until its runtime qualification and phase review pass.

### Negative Consequences / Tradeoffs
- Additional stateful workload (Redis) to supervise on Kubernetes.
- Eventual consistency window for read replicas bounded to $\le 100\text{ms}$.

### Explicit Non-Claims
- **Single-Physical-Host Boundary:** Evaluated within the local multi-node `kind` Kubernetes cluster.
- **No Cloud Managed PaaS:** Does not claim AWS ElastiCache, MemoryDB, or Aurora Global Database capabilities.
- **Verification Pending:** Acceptance of this ADR authorizes the design; it does not certify that the design is fully wired or that its latency, CPU, consistency, and failure targets have been achieved.
