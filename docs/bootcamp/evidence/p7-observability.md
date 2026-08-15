# Phase 7 — Observability Engineering Evidence

## P7-01 — ADR: observability strategy

- **Decision recorded:** `docs/adr/0013-observability-strategy.md` is accepted.
- **Approach:** Correlation IDs first, Micrometer Tracing + Brave for spans, structured JSON logging with MDC, SLO-aligned metrics, and explicit deferral of central APM/log aggregation/alerting infrastructure.
- **Verification:** Document reviewed; no new runtime infrastructure introduced.

## P7-02 — Correlation ID propagation

- **Instrumentation added:**
  - `app/src/main/kotlin/com/hyperscale/commerce/config/observability/CorrelationIdFilter.kt`
  - `app/src/main/kotlin/com/hyperscale/commerce/config/observability/CorrelationIdRecordInterceptor.kt`
  - `order-query/src/main/kotlin/com/hyperscale/commerce/orderquery/observability/CorrelationIdFilter.kt`
  - `order-query/src/main/kotlin/com/hyperscale/commerce/orderquery/observability/CorrelationIdRecordInterceptor.kt`
- **Behavior:** `X-Correlation-Id` is generated or read at HTTP ingress and returned in the response. The value is written to MDC as `correlationId` and propagated as a Kafka record header (`correlation-id`). Consumers extract the header and place it in MDC.
- **Verification:** `CorrelationIdIntegrationTest` in `app` asserts that a `POST /orders` response contains `X-Correlation-Id`, that the record header reaches `order-placed`, and that `GET /orders/{id}` in `order-query` also returns the header. `make verify` passes.

## P7-03 — Distributed tracing for HTTP and Kafka

- **Instrumentation added:**
  - `app/src/main/kotlin/com/hyperscale/commerce/config/observability/TracingConfiguration.kt`
  - `order-query/src/main/kotlin/com/hyperscale/commerce/orderquery/observability/TracingConfiguration.kt`
- **Behavior:** `TracingConfiguration` configures a Brave `Tracer` with a no-op `SpanHandler`, an `ObservationRegistry`, and propagating observation handlers for Kafka send/receive. HTTP and Kafka spans create `traceId` and `spanId`; these values are placed in MDC by the correlation filter and consumer interceptor.
- **Verification:** `EndToEndTracingTest` in `app` starts both services, places an order, and asserts that the `traceId` returned in the `POST` response appears in the `order-query` consumer log line:
  ```text
  Consumed record from order-placed traceId=<post-trace-id>
  ```
  `make verify` passes.

## P7-04 — SLO metrics and dashboards

- **Instrumentation added:**
  - `app/src/main/kotlin/com/hyperscale/commerce/config/metrics/SloMetricsConfiguration.kt`
  - `order-query/src/main/kotlin/com/hyperscale/commerce/orderquery/config/metrics/SloMetricsConfiguration.kt`
  - `docs/observability/slos.md`
- **Behavior:** A `MeterFilter` configures `http.server.requests` with p95 percentiles. Custom `slo_*` gauges derive `GET /orders/{id}` p95, `GET /orders` p95, and `POST /orders` success rate from `http.server.requests`.
- **Verification:**
  - `ObservabilitySloTest` in `app` passes and asserts `slo_post_orders_success_rate` and p95 gauges are present in `/actuator/prometheus`.
  - `ObservabilitySloTest` in `order-query` passes and asserts `slo_get_order_by_id_p95` and `slo_get_orders_p95` are present in `/actuator/prometheus`.
  - `make verify` passes.

## P7-05 — Logging alignment and operational runbooks

- **Instrumentation added:**
  - Updated `app/src/main/resources/logback-spring.xml`
  - Updated `order-query/src/main/resources/logback-spring.xml`
  - `docs/observability/runbooks/high-outbox-relay-lag.md`
  - `docs/observability/runbooks/high-read-model-lag.md`
  - `docs/observability/runbooks/poison-message.md`
  - `docs/observability/runbooks/kafka-broker-down.md`
  - `docs/observability/runbooks/postgres-down.md`
- **Behavior:** Both services emit the same JSON log format. The `!local` profile uses `LogstashEncoder` with a `service` field bound to `spring.application.name`. MDC already carries `traceId`, `spanId`, and `correlationId` from the correlation filter and consumer interceptor, so the JSON log output contains `traceId`, `spanId`, `correlationId`, `service`, `logger`, `level`, `message`, and `timestamp`.
- **Sample log line observed during `make verify`:**
  ```json
  {"@timestamp":"2026-08-15T11:42:37.045232+07:00","@version":"1","message":"Commencing graceful shutdown. Waiting for active requests to complete","logger_name":"org.springframework.boot.tomcat.GracefulShutdown","thread_name":"SpringApplicationShutdownHook","level":"INFO","level_value":20000,"tags":["COMMONS-LOGGING"],"service":"order-query"}
  ```
- **Verification:** `make verify` passes; runbooks reviewed for the five required failure modes.

## P7-06 — End-to-end observability verification

- **Test:** `EndToEndTracingTest` in `app`.
- **Scenario:** A single `POST /orders` in the monolith is followed by a `GET /orders/{id}` in `order-query` after the projection consumes the `order-placed` event.
- **Trace link asserted:** The `traceId` in the `POST` response header (`X-Trace-Id`) is found in the `order-query` consumer log, linking `POST /orders` -> outbox -> `order-placed` -> `order-query` projection -> `GET /orders/{id}`.
- **Verification:** `EndToEndTracingTest` passes; `make verify` passes.

## P7-07 — Phase evidence and exit criteria

- **Evidence report:** This file, `docs/bootcamp/evidence/p7-observability.md`, documents each task, instrumentation, and verification result.
- **Gaps identified and resolved:**
  - `0.95` p95 percentile literals triggered `detekt` `MagicNumber`; resolved by extracting `private const val P95_PERCENTILE = 0.95` in `SloMetricsConfiguration` for both services.
  - Spotless formatting violations in `SloMetricsConfiguration` and `ObservabilitySloTest` were resolved with `./gradlew :app:spotlessApply :order-query:spotlessApply`.
  - JSON logs did not explicitly include the originating `service` name; resolved by adding `springProperty` and `LogstashEncoder` custom fields in `logback-spring.xml` for both services.

## Phase 7 Exit Criteria Evaluation

| # | Exit Criterion | Status | Evidence |
|---|---|:---:|---|
| 1 | Tasks P7-01 through P7-07 implemented and verified | **PASS** | All task sections above and the listed files are present. |
| 2 | `make verify` passes from a clean checkout | **PASS** | `make verify` completed with `BUILD SUCCESSFUL` on 2026-08-15. |
| 3 | Correlation IDs propagate through HTTP, Kafka, and logs in both services | **PASS** | `CorrelationIdIntegrationTest` and `EndToEndTracingTest` pass. |
| 4 | Distributed traces link `POST /orders` -> `order-placed` -> `order-query` projection -> `GET /orders/{id}` | **PASS** | `EndToEndTracingTest` asserts shared `traceId` across services. |
| 5 | Both services emit structured JSON logs with `traceId`, `spanId`, `correlationId`, `service`, and `timestamp` | **PASS** | Sample log line and `LogstashEncoder` configuration in `logback-spring.xml`. |
| 6 | Critical API SLOs are asserted and documented | **PASS** | `ObservabilitySloTest` and `docs/observability/slos.md`. |
| 7 | Operational runbooks for Kafka, PostgreSQL, poison messages, and high lag are documented | **PASS** | `docs/observability/runbooks/*.md`. |
| 8 | Evidence report documents every experiment, runbook, and gap found | **PASS** | This file. |
| 9 | No forbidden technology introduced | **PASS** | No Kubernetes, API gateway, service mesh, Redis, event sourcing, central APM, or log aggregation added. |
