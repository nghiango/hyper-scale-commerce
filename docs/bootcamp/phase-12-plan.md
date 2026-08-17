# HyperScale Commerce — Phase 12 Implementation Plan

**Phase:** Phase 12 — High-Throughput Caching, Multi-Replica Scheduling & Storage Lifecycle Management  
**Status:** **COMPLETED**

**Author:** AI Distributed Systems Architect  
**Date:** 2026-08-17  

---

## 1. Phase Objective

The objective of Phase 12 is to implement and empirically verify high-throughput read caching, lock-free multi-replica background worker coordination, and automated database storage lifecycle management:
1. **Multi-Replica Outbox Polling (ADR-0021):** Refactor outbox batch claiming to use PostgreSQL `FOR UPDATE SKIP LOCKED` for zero lock contention across concurrent application replicas.
2. **Multi-Tier In-Memory Caching with Stampede Mitigation (ADR-0021):** Implement Caffeine in-memory near-caching on read paths (`GET /catalog/products`, `GET /orders/{id}`) with singleflight mutex protection against thundering herds.
3. **Event-Driven Cache Invalidation (ADR-0021):** Invalidate and update cached records upon `OrderPlaced` and `OrderCancelled` domain events.
4. **Automated Storage Lifecycle & Data Pruning (ADR-0021):** Implement scheduled batch pruning for published outbox events and expired idempotency keys to prevent table and index bloat.
5. **High-Throughput Qualification:** Verify $>10,000\text{ RPS}$ read throughput with $>95\%$ cache hit ratios, sub-2ms p95 latency, and zero data loss.

---

## 2. Why This Phase Exists

- **Database Saturation on Read Spikes:** Under flash sales and high-concurrency traffic ($>10,000\text{ RPS}$), querying PostgreSQL directly for every catalog and order lookup creates severe database CPU saturation and pool exhaustion.
- **Worker Lock Contention in Multi-Pod Clusters:** When `app` is horizontally scaled to multiple replicas, naive outbox queries cause row lock serialization delays. `FOR UPDATE SKIP LOCKED` allows lock-free parallel outbox claiming.
- **Table & Index Bloat:** Millions of transactions accumulate in `"order".outbox_events` and `"order".idempotency_keys`. Unpruned historical rows degrade B-Tree index scan performance. Scheduled batch pruning bounds table sizes.

---

## 3. Starting Architecture / State

- **Services:** `app` (Port 8080) and `order-query` (Port 8081).
- **Persistence:** PostgreSQL 16 with schemas `catalog`, `order`, `inventory`, `order_query`.
- **Messaging:** Kafka 3.7.0 with `order-placed`, `order-cancelled`, `inventory-reservation-failed`.
- **Capabilities:** Phase 11 certified with API idempotency deduplication, choreographed saga compensations, adaptive load shedding, and schema evolution tests.

---

## 4. Target Architecture / State

- **Outbox Relay:** `JooqOutboxRepository` claims unpublished events using `FOR UPDATE SKIP LOCKED`, supporting unbounded horizontal replica scaling without contention.
- **Read Caching:** In-memory Caffeine caches for `CatalogService` and `OrderQueryService` with TTL, maximum size bounds, and singleflight cache stampede protection.
- **Cache Invalidation:** Consumers in `app` and `order-query` invalidate cached product and order entries immediately upon receiving domain events.
- **Pruning Engine:** Scheduled background worker in `app` cleans up published outbox records ($>7\text{d}$) and expired idempotency keys ($>24\text{h}$) in non-blocking batches.

---

## 5. Problems This Phase Addresses

1. Database connection pool exhaustion and CPU spikes on high-volume read paths.
2. Thundering herd / cache stampede phenomena during flash sale cache expirations.
3. Lock contention between horizontally scaled outbox relay instances.
4. Long-term database disk and index bloat from accumulated outbox and idempotency rows.

---

## 6. Architecture Changes

- Update `JooqOutboxRepository.kt` to use `FOR UPDATE SKIP LOCKED`.
- Add `com.github.ben-manes.caffeine:caffeine` dependency.
- Create `CatalogCacheConfig.kt` and `OrderCacheConfig.kt` in `app` and `order-query`.
- Add event-driven cache invalidation hooks in `OrderPlacedProjection.kt` and `OrderCancelledProjection.kt`.
- Create `StoragePruningService.kt` in `app` with scheduled batch pruning methods.

---

## 7. Technology Changes

- **Caffeine (2.9.3 / 3.x):** High-performance near-cache implementation.
- **PostgreSQL 16 `SKIP LOCKED`:** Native concurrency primitive.
- No new external infrastructure servers (e.g. Redis) required; retains clean, lightweight architecture.

---

## 8. Non-Functional Requirements

- **Read Latency (Cached):** $\text{p95} < 2\text{ms}$ on `GET /catalog/products` and `GET /orders/{id}`.
- **Cache Hit Ratio:** $\ge 95\%$ under repetitive read traffic.
- **Outbox Concurrency:** Zero lock wait timeouts under concurrent multi-worker polling.
- **Pruning Safety:** Pruning queries operate in small batches (e.g. 1,000 rows) with `WHERE published_at IS NOT NULL` to avoid table-level locks.

---

## 9. Performance Expectations

- Catalog browsing throughput $> 15,000\text{ RPS}$ on standard container resources.
- Critical API aggregate p95 reduced by $\ge 50\%$ compared to non-cached baseline.

---

## 10. Reliability Expectations

- Invalidation guarantees zero stale reads for modified/cancelled orders.
- Cache failure falls back gracefully to direct PostgreSQL read without throwing exceptions.

---

## 11. Observability Requirements

- Prometheus metrics:
  - `cache_gets_total{cache="catalog|order",result="hit|miss"}`
  - `storage_pruned_rows_total{table="outbox_events|idempotency_keys"}`

---

## 12. Security Considerations

- In-memory cache entries do not leak across tenant boundaries (tenant/id isolated keys).
- Pruning jobs verify row status before deletion to prevent accidental deletion of uncommitted or unpublished data.

---

## 13. Data Considerations

- Zero data loss: only published outbox events with `published_at IS NOT NULL` older than retention threshold are eligible for pruning.

---

## 14. Explicitly Out-of-Scope Capabilities

- External Redis cluster deployment (in-memory Caffeine is sufficient and eliminates distributed cache network hop latency).
- Database hard sharding across multiple physical PostgreSQL nodes.

---

## 15. Dependencies on Previous Phase

- Builds directly upon Phase 11 verified saga compensations, idempotency schemas, and load test scripts.

---

## 16. Risks & Mitigations

| Risk | Impact | Mitigation Strategy |
|---|---|---|
| Cache memory exhaustion under millions of unique keys | Out of Memory Error | Strict Caffeine `maximumSize` limits (e.g. 50,000 entries) with LRU/TinyLFU eviction |
| Long-running pruning DELETE queries locking active outbox transactions | Write latency spikes | Batch limit (`LIMIT 1000`) and index on `published_at` and `expires_at` |
| Stale order state in read cache | Customer sees outdated order status | Proactive synchronous invalidation on projection update |

---

## 17. ADRs Required

- **ADR-0021:** Multi-Tier Caching, Non-Blocking Multi-Replica Scheduling, and Storage Lifecycle Strategy (Approved).

---

## 18. Ordered Implementation Tasks

### P12-01 — Lock-Free Multi-Replica Outbox Polling (`SKIP LOCKED`)
- **Objective:** Update `JooqOutboxRepository` to claim unpublished outbox events using `FOR UPDATE SKIP LOCKED`.
- **Dependencies:** None.
- **Scope:** `JooqOutboxRepository.kt`, unit and integration tests.
- **Acceptance Criteria:** Multiple concurrent claim calls successfully retrieve non-overlapping batches with zero lock blocking.

### P12-02 — Multi-Tier Caching & Cache Stampede Protection
- **Objective:** Implement Caffeine in-memory L1 caching for catalog browsing (`GET /catalog/products`) and order queries (`GET /orders/{id}`) with singleflight stampede prevention.
- **Dependencies:** P12-01.
- **Scope:** Caffeine integration in `app` and `order-query`, metrics.
- **Acceptance Criteria:** Cache hits return in $< 2\text{ms}$; cache misses populate cache safely; metrics track hits/misses.

### P12-03 — Event-Driven Cache Invalidation
- **Objective:** Implement proactive cache invalidation on order placement and cancellation events in `order-query`.
- **Dependencies:** P12-02.
- **Scope:** `OrderPlacedProjection.kt`, `OrderCancelledProjection.kt`, cache manager.
- **Acceptance Criteria:** Cached order query reflects updated status immediately after event projection.

### P12-04 — Automated Storage Lifecycle & Data Pruning Engine
- **Objective:** Implement scheduled batch pruning for published outbox events and expired idempotency keys.
- **Dependencies:** P12-01.
- **Scope:** `StoragePruningService.kt`, repository pruning methods, configuration.
- **Acceptance Criteria:** Pruning job safely purges published events and expired keys without affecting uncommitted or recent records.

### P12-05 — High-Throughput Caching & Pruning Qualification
- **Objective:** Execute k6 load qualification scenario verifying $>10,000\text{ RPS}$ read throughput, $>95\%$ cache hit ratios, and zero data reconciliation errors.
- **Dependencies:** P12-01 through P12-04.
- **Scope:** `performance/k6/cached-throughput.js`, `make load-cache`.
- **Acceptance Criteria:** p95 latency $< 10\text{ms}$ under load; 100% cross-schema data reconciliation verified.

### P12-06 — Phase 12 Review & Final Platform Dossier
- **Objective:** Consolidate evidence in `docs/bootcamp/evidence/p12-caching-and-lifecycle.md` and complete phase certification.
- **Dependencies:** P12-01 through P12-05.
- **Scope:** Evidence documentation and review sign-off.
- **Acceptance Criteria:** All Phase 12 exit criteria satisfied.

---

## 19. Dependency Graph

```text
P12-01 ──+──> P12-02 ──+──> P12-03 ──+
         |                           |
         +──> P12-04 ────────────────+──> P12-05 ──> P12-06
```

---

## 20. Verification Requirements for Every Task

- Automated build and unit tests pass (`make verify`).
- Cache hit/miss and pruning metrics verified.
- 100% cross-schema data reconciliation verified.

---

## 21. Phase Exit Criteria

1. Outbox relay claims rows using `FOR UPDATE SKIP LOCKED`.
2. Caffeine L1 caching active on catalog and order query paths with $>95\%$ hit ratio under load.
3. Event-driven cache invalidation verified with zero stale reads.
4. Storage pruning engine verified to safely prune published and expired records.
5. High-throughput performance qualification passes with p95 $< 10\text{ms}$ and 100% data reconciliation.
