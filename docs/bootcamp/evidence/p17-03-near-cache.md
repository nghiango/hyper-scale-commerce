# P17-03 Completion Record: Multi-Level Near-Cache

- **Task:** P17-03 — Multi-Level Near-Cache Implementation
- **Status:** PASSED
- **Date:** 2026-08-17

## Implemented

- Caffeine L1 plus authenticated Redis L2 read-aside caches in `app` and
  `order-query`.
- Redis TTL writes, shared cross-process keys, L1 warming from L2, and fail-open
  database loading when Redis operations fail.
- Explicit Redis host, port, password, connect timeout, and read timeout for
  local, Compose, and Kubernetes deployments.
- Dependency inversion in Order Query: the cache port and near-cache remain in
  the application layer while the Redis adapter remains configuration-side.

## Verification

- Focused app and Order Query cache tests: PASSED.
- Real Redis 7.2 Testcontainers adapter interoperability and TTL test: PASSED.
- Full unit and architecture suite (`./gradlew test`): PASSED.
- Rendered Kubernetes Redis/application wiring and runtime checks: PASSED.

## Boundary

Kafka invalidation broadcasting is verified separately by P17-04.
