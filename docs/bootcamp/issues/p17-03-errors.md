# P17-03 Error History

## 2026-08-17 — Order Query near-cache type inference

- **Operation:** Focused app and Order Query cache test run.
- **Result:** FAILED during `:order-query:compileKotlin`.
- **Error:** Kotlin could not infer `NearCache` type parameter `K` in
  `OrderQueryService`.
- **Root cause:** The cache property constructor omitted explicit generic types,
  while its key type was only established later inside loader usage.
- **Resolution plan:** Declare `NearCache<Long, OrderDto>` explicitly and rerun
  the identical focused verification.

### Attempt 1 resolution

- Added the explicit cache type.
- Focused app and Order Query unit tests passed.

## 2026-08-17 — Redis integration-test container visibility

- **Operation:** Focused Redis Testcontainers integration test.
- **Result:** FAILED during integration-test compilation.
- **Error:** Public container property exposed a private nested container type.
- **Root cause:** Kotlin visibility mismatch in the test fixture.
- **Resolution plan:** Make the nested test-container type visible and rerun the
  identical integration test.

### Attempt 2 resolution

- Corrected the fixture visibility.
- The real Redis adapter interoperability integration test passed.

## 2026-08-17 — Order Query architecture dependency violation

- **Operation:** Full `./gradlew test` verification.
- **Result:** FAILED in `OrderQueryArchitectureTest`.
- **Error:** The application layer depended on `orderquery.config.cache`.
- **Root cause:** The first implementation placed the cache port and near-cache
  abstraction beside the Redis adapter in the configuration package.
- **Resolution plan:** Move the cache port and near-cache abstraction into the
  application layer, leave the Redis adapter in configuration, and rerun the
  complete unit and architecture suite.

### Resolution

- Moved the cache port and `NearCache` into `orderquery.application.cache`.
- Kept `RedisL2CacheStore` and its bean wiring in `orderquery.config.cache`.
- **Verification:** Full `./gradlew test`, focused real-Redis adapter integration,
  and `make k8s-redis-verify` all passed.
- **Status:** RESOLVED.
