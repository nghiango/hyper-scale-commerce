# P17-04 Completion Record: Event-Driven Cache Invalidation

- **Task:** P17-04 — Event-Driven Cache Invalidation Bus
- **Status:** PASSED
- **Date:** 2026-08-17

## Implemented

- Durable `catalog-cache-evict`, `inventory-cache-evict`, and
  `order-cache-evict` HA Kafka topics with RF/min-ISR preflight coverage.
- Versioned `CacheInvalidationEvent` publication from catalog eviction paths and
  Order Query projection-driven cache eviction paths.
- Per-pod broadcast listeners with unique consumer groups; listeners evict L1
  locally while the mutation-processing pod removes the shared L2 key.
- Explicit JSON field parsing for cross-Jackson-version contract compatibility.

## Verification

- Publisher/listener unit tests in both services: PASSED.
- Catalog mutation-publication test: PASSED.
- Two independent consumer groups received one real Kafka event and evicted
  their separate pod-local caches: PASSED.
- Warmed steady-state publish-to-both-pods propagation: `< 50 ms` assertion PASSED.
- Full `./gradlew test`: PASSED.
- Kafka topic scripts: shell syntax and required-topic assertions PASSED.
