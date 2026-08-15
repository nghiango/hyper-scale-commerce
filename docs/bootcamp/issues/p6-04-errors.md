# P6-04 Error History

## Attempt 1 — FAILED

**Verification:** `:app:integrationTest --tests "com.hyperscale.commerce.resilience.PostgresOutageIntegrationTest"` and `:order-query:integrationTest --tests "com.hyperscale.commerce.orderquery.resilience.PostgresOutageIntegrationTest"`

**Errors:**

- App: `org.springframework.jdbc.BadSqlGrammarException: relation "order.outbox_events" does not exist` in the `outboxPublished` assertion.
- Order-query: `org.springframework.jdbc.BadSqlGrammarException: relation "order_query.order_read_model" does not exist` in `readModelCount`.

**Observation:** Both applications started and passed HTTP readiness, and the app `POST /orders` returned `201`. Despite the jOOQ-based write path appearing to work, `JdbcTemplate` queries for the expected tables fail, suggesting the target table or schema may not exist in the `JdbcTemplate` DataSource at test time.

## Attempt 2 — FAILED

**Verification:** `:app:integrationTest --tests "com.hyperscale.commerce.resilience.PostgresOutageIntegrationTest"` and `:order-query:integrationTest --tests "com.hyperscale.commerce.orderquery.resilience.PostgresOutageIntegrationTest"`

**Errors and gaps:**

- The shared PostgreSQL helper pauses database processes using `kill -STOP` / `kill -CONT` instead of the approved Testcontainers `stop()` / `start()` lifecycle.
- The app experiment does not query its committed outbox row after recovery; its in-memory publication metric alone cannot establish database persistence.
- The order-query experiment does not re-read the pre-outage projection after recovery, so committed read-model loss would be undetected.

**Root cause:** The initial experiments substituted process signalling for the phase's specified container lifecycle and used indirect or incomplete persistence checks.

**Planned fix:** Use the shared Testcontainers lifecycle methods and add direct, schema-qualified post-recovery assertions for the committed outbox row and the pre-outage read-model row.

## Attempt 3 — FAILED

**Verification:** `./gradlew --no-daemon :app:integrationTest --tests "com.hyperscale.commerce.resilience.PostgresOutageIntegrationTest" :order-query:integrationTest --tests "com.hyperscale.commerce.orderquery.resilience.PostgresOutageIntegrationTest"`

**Error:** `ERROR: relation "order.outbox_events" does not exist` after the PostgreSQL restart.

**Root cause:** Testcontainers created a fresh PostgreSQL container on `start()`. Its default writable data layer was not retained, so the restarted database had no Flyway migrations or committed outbox row. The new direct assertion correctly detected the loss.

**Planned fix:** Bind a per-test temporary PostgreSQL data directory to `/var/lib/postgresql/data` so the Testcontainers stop/start lifecycle retains database state while keeping the fixture isolated and test-only.

## Attempt 4 — FAILED

**Verification:** `./gradlew --no-daemon :app:integrationTest --tests "com.hyperscale.commerce.resilience.PostgresOutageIntegrationTest"`

**Error:** Testcontainers timed out waiting for PostgreSQL's default log-message readiness check after restart, although the captured container log ended with `database system is ready to accept connections`.

**Root cause:** The recreated PostgreSQL container recovered its persisted data, but the default log-based readiness strategy did not observe the restart log line in time.

**Planned fix:** Use Testcontainers' listening-port readiness strategy for the two PostgreSQL outage fixtures; it verifies the service endpoint directly and avoids the restart log-capture race.

## Attempt 5 — PASSED

**Verification:** `./gradlew :app:integrationTest --tests "com.hyperscale.commerce.resilience.PostgresOutageIntegrationTest"` and `./gradlew :order-query:integrationTest --tests "com.hyperscale.commerce.orderquery.resilience.PostgresOutageIntegrationTest"`, then `make verify`.

**Result:** All P6-04 integration tests passed and `make verify` completed successfully.

**Applied fix:**

- Kept the approved Testcontainers `stop()` / `start()` lifecycle with a per-test, bind-mounted PostgreSQL data directory to retain committed state across restarts.
- Switched each PostgreSQL outage fixture to `Wait.forListeningPort()` so restart readiness is detected by the exposed port rather than log-line capture.
- Assigned distinct fixed host ports to the `app` (`5434` / `29094`) and `order-query` (`5435` / `29095`) P6-04 fixtures to prevent `setPortBindings` collisions during the full `make verify` run.
