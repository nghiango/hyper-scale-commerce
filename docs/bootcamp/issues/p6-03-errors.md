# P6-03 Error History

## Attempt 1 — FAILED

**Verification:** `:app:integrationTest --tests "com.hyperscale.commerce.resilience.KafkaOutageIntegrationTest"`

**Error:** `org.springframework.jdbc.BadSqlGrammarException: PreparedStatementCallback; bad SQL grammar [SELECT published FROM "order".outbox_events WHERE aggregate_id = ? AND event_type = ?]`

**Root cause:** The `outbox_events` table uses `published_at` (a `TIMESTAMPTZ`) to record publish time, not a `published` boolean column. The test query referenced a non-existent column.

**Fix:** Change the query to `SELECT count(*) FROM "order".outbox_events WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL` and check the returned count.

## Attempt 1 (retry) — PASSED

**Verification:** `:app:integrationTest --tests "com.hyperscale.commerce.resilience.KafkaOutageIntegrationTest"`

**Result:** `KafkaOutageIntegrationTest > outbox buffers events while kafka is down and the projection catches up when the broker returns() PASSED` after applying the `published_at` fix.

## Attempt 2 — FAILED

**Verification:** `:order-query:integrationTest --tests "com.hyperscale.commerce.orderquery.resilience.KafkaOutageIntegrationTest"`

**Error:** `java.lang.IllegalStateException: Mapped port can only be obtained after the container is started`

**Root cause:** The test stopped Kafka before starting `order-query`, but `startOrderQuery()` used `kafka.bootstrapServers`, which calls `getMappedPort()` on the stopped container.

**Fix:** Use the fixed host port (`PLAINTEXT://localhost:29093`) for the Spring `bootstrap-servers` and `localhost:29093` for the manual `KafkaProducer` instead of `kafka.bootstrapServers` and `getMappedPort()`.

## Attempt 2 (retry) — PASSED

**Verification:** `:order-query:integrationTest --tests "com.hyperscale.commerce.orderquery.resilience.KafkaOutageIntegrationTest"`

**Result:** `KafkaOutageIntegrationTest > starts without kafka and catches up when the broker returns() PASSED` after applying the fixed-port approach.
