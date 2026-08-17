# P17-04 Error History

## 2026-08-17 — Invalidation publisher compilation

- **Operation:** Focused app and Order Query invalidation test run.
- **Result:** FAILED during Kotlin compilation.
- **Errors:** `CatalogService` did not retain its injected invalidation service as
  a property; the Order Query Kafka publisher supplied a nullable record key.
- **Root cause:** The existing constructor parameter was previously used only in
  initialization, and the new publisher did not normalize cache-wide events to
  a non-null Kafka key.
- **Resolution plan:** Retain the catalog publisher as a private property and use
  the cache name as the ordering key for cache-wide invalidations.

### Attempt 1 result

- Production sources compiled and app invalidation tests passed.
- Order Query test compilation failed because Jackson 3 `ObjectMapper` does not
  expose the Jackson 2 `findAndRegisterModules` method.
- **Resolution:** Use the project's established Jackson 3 `ObjectMapper()` test
  construction and rerun the focused suite.

### Attempt 2 result

- Production code and publisher tests passed.
- Listener verification failed because a bare Jackson 3 mapper did not discover
  the Kotlin/time modules needed for `CacheInvalidationEvent`.
- **Resolution:** Build the test mapper with Jackson 3 module discovery, matching
  Spring Boot's configured mapper behavior.

### Attempt 3 diagnosis and resolution

- The captured test log showed that Jackson 3 still could not construct the
  Kotlin contract class in the Order Query module.
- Replaced contract-class deserialization in the listener with the same explicit
  JSON-tree field extraction already used by Order Query event projections.
  Publication continues to use the versioned contract.

## 2026-08-17 — App broadcast listener contract deserialization

- **Operation:** Two-consumer-group Kafka Testcontainers broadcast test.
- **Result:** FAILED; both pods retained the stale L1 value.
- **Root cause:** The app listener also could not construct the Kotlin contract
  from JSON with its runtime Jackson mapper.
- **Resolution plan:** Parse the versioned event fields explicitly in the app
  listener and rerun the same two-group Kafka test.

### Runtime resolution

- Explicit app event parsing fixed the stale-cache defect; the two-group
  broadcast test passed functionally.
- A subsequent 50 ms assertion measured 170 ms because the test polled its two
  consumers serially, unlike concurrently running pod listeners.
- **Resolution plan:** Poll both already-assigned consumer groups concurrently
  and measure publish-to-both-received latency.

### Propagation attempt 2

- Concurrent consumers still measured 167 ms because the first producer send
  included Kafka metadata/connection initialization.
- **Resolution plan:** Warm the already-assigned producer/consumer path before
  measuring the steady-state invalidation SLO, then make one final measurement.

### Resolution

- The warmed concurrent two-group Kafka test passed the `< 50 ms` assertion.
- Full unit and architecture tests passed.
- Cache topics were added to HA initialization and preflight checks.
- **Status:** RESOLVED.
