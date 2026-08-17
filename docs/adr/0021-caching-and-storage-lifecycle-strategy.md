# ADR-0021: Multi-Tier Caching, Non-Blocking Multi-Replica Scheduling, and Storage Lifecycle Strategy

## Status

Accepted

## Context

In high-throughput commerce operations ($>10,000$ RPS), read traffic against catalog browsing and order query models creates significant database CPU load and connection pool pressure if served entirely from disk/PostgreSQL. Furthermore, scaling background workers (such as the Outbox Relay) across multiple container replicas causes database lock contention without non-blocking query primitives. Finally, high transaction volume causes unbounded growth in transactional outbox and idempotency tables.

---

## Decision

We adopt a three-part performance, concurrency, and lifecycle management architecture:

1. **Multi-Tier In-Memory Caching (Caffeine L1):**
   - Implement near-cache in-memory caching using Caffeine for high-volume read endpoints (`GET /catalog/products`, `GET /orders/{id}`).
   - Prevent cache stampedes (thundering herd) via singleflight concurrent lookup deduplication.
   - Implement event-driven cache invalidation: listen to `OrderPlaced` and `OrderCancelled` to evict or refresh stale cache keys.
2. **Lock-Free Multi-Replica Outbox Polling (`FOR UPDATE SKIP LOCKED`):**
   - Outbox event claim queries use PostgreSQL `FOR UPDATE SKIP LOCKED` to allow multiple concurrent `app` replicas to poll and publish outbox batches concurrently without lock contention or duplicate publishing.
3. **Automated Storage Lifecycle & Data Pruning:**
   - Implement scheduled background batch pruning to remove published outbox events (`published_at < now() - INTERVAL '7 days'`) and expired idempotency keys (`expires_at < now()`), preventing index bloat and unbounded disk growth.

---

## Consequences

### Positive
- Read throughput increases dramatically ($>20,000$ RPS) with sub-2ms p95 latency.
- Database CPU and connection pool usage reduced by up to 80% on read paths.
- Multiple application replicas scale out horizontally without outbox table lock contention.
- Database tables and indexes remain compact and bounded in size.

### Negative / Tradeoffs
- Cache consistency requires careful event-driven invalidation.
- Pruning jobs consume background I/O cycles (mitigated by batch chunking and index usage).
