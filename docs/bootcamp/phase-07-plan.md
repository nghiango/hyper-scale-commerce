# Phase 07 — Observability

Status: **APPROVED**

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-06-plan.md`
- `docs/bootcamp/evidence/p6-resilience.md`
- `docs/adr/0006-kafka-event-broker.md`
- `docs/adr/0007-transactional-outbox.md`
- `docs/adr/0008-cqrs-order-query-side.md`
- `docs/adr/0010-extract-order-query-service.md`
- `docs/adr/0011-monorepo-module-data-ownership.md`
- `docs/adr/0012-resilience-strategy.md`
- The existing Phase 6 implementation (build, source, tests, evidence)

---

## 1. Phase objective

Add end-to-end observability to the two-deployable platform so that every
request, event, and failure can be correlated, traced, and measured against
SLOs without introducing new runtime infrastructure. The phase produces
instrumentation (correlation IDs, distributed traces, structured logging),
SLO-aligned metrics, operational runbooks, and documented evidence that the
platform is observable.

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. Modular Monolith
3. Performance Engineering
4. Event-Driven Architecture
5. CQRS
6. Service Extraction
7. Resilience Engineering
8. **Observability** ← this phase
9. Load Engineering
10. Chaos Engineering

Phase 6 proved that the platform tolerates Kafka, PostgreSQL, and partial
service outages without data loss and with bounded retries. The surviving and
recovery behaviors are now measurable, but they are not connected: a
`POST /orders` in `app` and the resulting `GET /orders/{id}` in `order-query`
cannot be traced as one logical flow, logs across the two services are not
correlated, and there are no SLO dashboards or operational runbooks. The
constitution's distributed-systems rules (§5) require failures to be
observable through metrics and logs, and the reliability rules (§7) require
end-to-end distributed tracing, correlation IDs, and operational
observability. Phase 7 closes that gap.

## 3. Starting architecture / state

| Item | State |
|---|---|
| Deployables | `app` (monolith, port 8080) and `order-query` (service, port 8081); `contracts` module shared |
| Communication | Kafka topic `order-placed`; DLQ `order-placed-dlq`; transactional outbox `order.outbox_events` with 1s relay |
| Consumers | `inventory` (in `app`) and `order-query` (projection); both idempotent with bounded retries and DLQ routing |
| Database | One PostgreSQL 16 instance; schemas `catalog`, `order`, `inventory` (app) and `order_query` (order-query); per-service Flyway |
| Resilience mechanisms | Outbox durability, at-least-once delivery, idempotent consumers, bounded retries, DLQ, startup-order and dependency-failure independence (P6) |
| Metrics | `events_published_total`, `events_consumed_total`, `events_dlq_total`, `outbox_relay_lag`, `order_read_model_lag_seconds`, HTTP `http_server_requests`, actuator health |
| Logging | Structured JSON logging from both services |
| Tests | Testcontainers (PostgreSQL + Kafka); resilience experiments in `make verify` |
| Evidence | `p6-resilience.md` (outage and consumer resilience experiments) |
| Docs | ADR-0006 through ADR-0012 |

## 4. Target architecture / state

The architecture is unchanged in shape. The target is connected, SLO-aligned
observability:

```text
Client
  |
  v
app (monolith)                                order-query
POST /orders                                  GET /orders/{id}
GET /catalog/*                                GET /orders?page=&size=
Inventory consumer
      |                                           ^
      |                                           |
      v                                           |
order.outbox_events                             |
      |                                           |
      +--> Kafka order-placed --------------------+
               (trace + correlation headers)
```

- Every HTTP request and Kafka record carries a `correlation-id` and a trace
  context (`traceId`/`spanId`) so that `POST /orders` → `order-placed` →
  projection → `GET /orders/{id}` is one trace.
- Both services emit JSON logs containing `traceId`, `spanId`, and
  `correlationId` via SLF4J MDC.
- Critical API SLOs (`GET /orders/{id}` p95 < 100ms, `GET /orders` p95 < 200ms,
  `POST /orders` error rate) are asserted in tests and documented in runbooks.
- `outbox_relay_lag` and `order_read_model_lag_seconds` are visible in the
  context of the originating request.
- Operational runbooks explain how to triage Kafka broker, PostgreSQL, poison
  message, and high-lag symptoms using logs, metrics, and traces.

## 5. Problems this phase addresses

- A `POST /orders` in `app` and the resulting `GET /orders/{id}` in
  `order-query` cannot be correlated without manual log inspection.
- Logs from `app` and `order-query` are not correlated by a shared identifier.
- There is no end-to-end trace across the HTTP → Kafka → consumer chain.
- SLO metrics are present but not tied to per-request traces and not documented
  as dashboards/runbooks.
- Operational recovery (from `p6-resilience.md`) is documented, but triage
  procedures for the new observability signals are not.

## 6. Architecture changes

- No runtime topology change: the two-deployable, event-only architecture from
  Phase 5/6 is preserved.
- Add a correlation ID assigned at the first ingress point and propagated
  through HTTP headers, Kafka record headers, and logs.
- Add distributed tracing with `Micrometer Tracing` and the `Brave` bridge.
  HTTP filters and `spring-kafka` producer/consumer interceptors create and
  propagate spans without a remote collector.
- Extend the existing structured JSON log output with MDC fields for
  `traceId`, `spanId`, and `correlationId`.
- Add or align SLO-aligned metrics for critical APIs and the end-to-end lag
  from `POST /orders` to read-model visibility.
- Add operational runbooks and a `p7-observability.md` evidence report.

## 7. Technology changes

- **No new runtime infrastructure.** The runtime stack is unchanged in shape:
  Kotlin, Spring Boot, Kafka, PostgreSQL, Flyway, jOOQ.
- **Allowed additions:**
  - `Micrometer Tracing` for the tracing abstraction.
  - `Brave` bridge for trace/span generation and `B3`/W3C context propagation.
  - `spring-kafka` `RecordInterceptor` / `ProducerListener` for Kafka trace
    propagation.
  - `SLF4J MDC` (existing) populated with trace and correlation identifiers.
- **Explicitly deferred:**
  - Central APM backends (Zipkin, Jaeger, Tempo) and log aggregation platforms
    (ELK, Loki, Fluentd).
  - Kubernetes/service-mesh observability.
  - Real user monitoring (RUM), synthetic probes, continuous profiling.
  - Alerting infrastructure and on-call routing (operational runbooks only).

## 8. Non-functional requirements

- `make verify` passes from a clean checkout and includes the new observability
  experiments.
- All new Kotlin code passes `spotlessCheck` and `detekt`.
- No forbidden technology is introduced.
- Tracing and correlation propagation must be transparent to business logic and
  must not break existing integration tests.
- MDC and trace context must be cleared correctly to avoid leakage between
  concurrent or reused threads.
- End-to-end trace latency overhead must not exceed 5% of the critical API p95
  at the defined SLO load.

## 9. Performance expectations

Phase 7 does not claim new latency targets. Existing expectations carry over
and must not regress:

- `GET /orders/{id}` p95 under 100ms at 100 concurrent requests.
- `GET /orders` p95 under 200ms at 100 concurrent requests.
- `POST /orders` behavior and end-to-end read-model visibility must not
  regress.

## 10. Reliability expectations

- **Trace continuity:** a `POST /orders` in `app` produces a trace that is
  propagated through the outbox relay, Kafka `order-placed` topic, and
  `order-query` projection, and is queryable in the logs of both services.
- **Correlation fallback:** if a request or message has no correlation or trace
  header, a new one is generated and used for the remainder of the flow.
- **Consumer resilience unchanged:** trace and correlation propagation must not
  interfere with idempotency, bounded retries, or DLQ behavior.
- **Observability under failure:** logs and metrics remain consistent during
  Kafka and PostgreSQL outages; trace context is not lost when a service
  restarts.

## 11. Observability requirements

- **Correlation IDs:** every HTTP request and every Kafka record carries a
  `correlation-id` header/record header.
- **Distributed traces:** HTTP endpoints and Kafka producer/consumer operations
  create Micrometer spans with `traceId` and `spanId`.
- **Structured logs:** JSON log lines include `traceId`, `spanId`,
  `correlationId`, `service`, `logger`, `level`, `message`, and `timestamp`.
- **SLO metrics:** critical API timings and error rates are exposed as
  Prometheus-compatible metrics with `method`, `uri`, `outcome`, and `status`
  tags.
- **End-to-end lag:** `outbox_relay_lag` and `order_read_model_lag_seconds` are
  asserted in the context of a single trace.
- **Actuator health:** dependency health indicators (`db`, `kafka`) remain
  visible and are documented in runbooks.

## 12. Security considerations

- Correlation and trace IDs are random non-PII identifiers.
- Logs must not include full request bodies, card data, or passwords.
- Trace headers do not carry authentication tokens or secrets.
- No external APM or telemetry backend is called by the application code.

## 13. Data considerations

- No database schema changes are planned.
- Kafka record headers are additive and backward-compatible.
- Trace/correlation identifiers are stored only in logs and not in the business
  tables.
- Event payload schema remains additive; no new required fields are introduced.

## 14. Explicitly out-of-scope capabilities

- Central APM collectors (Zipkin, Jaeger, Tempo) and log aggregation
  (ELK/Loki/Fluentd).
- Kubernetes, service mesh, or API gateway observability.
- Real-user monitoring, synthetic probes, continuous profiling.
- Automated alerting and on-call routing (runbooks only).
- Circuit breakers, bulkheads, backpressure, rate limiting, load shedding
  (Phase 6 already evaluated; revisit in later phases when needed).

## 15. Dependencies on the previous phase

Phase 7 depends on the successful completion of Phase 6:

- P6-01 through P6-07 are complete and verified, and the Phase 6 phase-review
  has passed.
- `make verify` passes from a clean checkout.
- Two deployables exist (`app`, `order-query`) with per-service Flyway, jOOQ,
  Kafka consumers, DLQ, bounded retries, actuator endpoints, and resilience
  metrics.
- `docs/bootcamp/current-phase.md` is advanced to Phase 07 before
  implementation begins.

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **MDC/trace context leakage** between web, scheduler, and Kafka threads | Medium | Clear MDC in a `OncePerRequestFilter`, `TaskDecorator`, and Kafka consumer post-processing hooks |
| **Tests break because headers/assertions assume no trace headers** | Medium | Add test helpers and update existing assertions to ignore new headers where appropriate |
| **Tracing overhead raises p95 latency** | Medium | Measure in `CatalogSloVerificationTest` and `ServiceExtractionE2ETest`; use sampling/no-op Brave reporter |
| **Kafka trace header propagation conflicts** with `spring-kafka` error handling | Low | Propagate only in `RecordInterceptor` and `ProducerListener`; keep consumer retry/DLQ logic untouched |
| **Scope creep toward full APM stack** | Medium | Explicitly deferred (§14); only instrumentation and local logs/metrics for this phase |

## 17. ADRs that may be required

- **ADR-0013 — Observability strategy.** Required: documents the approach to
  correlation IDs, distributed tracing, structured logging, and SLOs, and the
  deferral of central APM backends and alerting infrastructure.

## 18. Ordered implementation tasks

### P7-01 — ADR: observability strategy

- **Objective:** Record the observability approach before instrumentation is
  added.
- **Context:** AGENTS.md requires ADRs for significant architectural decisions;
  observability is a cross-cutting concern.
- **Dependencies:** Phase 6 complete, phase-review passed.
- **Scope:** Documentation only.
- **Implementation requirements:**
  - Create ADR-0013 covering: correlation ID generation and propagation,
    distributed tracing with `Micrometer Tracing` + `Brave`, structured
    logging with MDC, SLO-aligned metrics, and the explicit deferral of
    central APM backends, log aggregation, and alerting infrastructure.
- **Acceptance criteria:**
  - ADR-0013 exists under `docs/adr/` and is accepted.
  - No new runtime technology is introduced outside §7.
- **Verification requirements:** Document review; no automated tests.
- **Expected files/components:**
  - `docs/adr/0013-observability-strategy.md`
- **Architecture impact:** Records the phase's observability decisions.
- **Out of scope:** Implementation of filters or interceptors.

### P7-02 — Correlation ID propagation

- **Objective:** Generate a `correlation-id` at ingress and propagate it through
  HTTP, Kafka, and logs.
- **Context:** Correlation is the foundation for end-to-end observability and
  does not require a tracing backend.
- **Dependencies:** P7-01.
- **Scope:** `app` and `order-query` source and tests.
- **Implementation requirements:**
  - Add an HTTP `OncePerRequestFilter` in both services that reads
    `X-Correlation-Id` or generates a UUID and puts it in MDC.
  - Add a `ProducerListener`/interceptor in `app` that copies the MDC
    `correlation-id` into a Kafka record header (`correlation-id`).
  - Add a `RecordInterceptor` in `order-query` and `app` inventory consumer
    that reads the `correlation-id` record header and puts it in MDC.
  - Ensure MDC is cleared after request/record processing.
- **Acceptance criteria:**
  - A request without a `X-Correlation-Id` header receives one in the response
    and all related logs contain `correlationId`.
  - A Kafka record produced by `app` carries a `correlation-id` header that is
    visible in `order-query` logs.
  - `make verify` passes.
- **Verification requirements:** Run new integration tests; run `make verify`.
- **Expected files/components:**
  - `app/src/main/kotlin/.../shared/observability/CorrelationIdFilter.kt`
  - `app/src/main/kotlin/.../shared/observability/KafkaCorrelationProducerListener.kt`
  - `order-query/src/main/kotlin/.../shared/observability/CorrelationIdRecordInterceptor.kt`
  - `app/src/integrationTest/kotlin/.../observability/CorrelationIdIntegrationTest.kt`
- **Architecture impact:** Adds cross-cutting request/record context without
  changing business logic.
- **Out of scope:** Distributed tracing spans; that is P7-03.

### P7-03 — Distributed tracing for HTTP and Kafka

- **Objective:** Create and propagate `Micrometer Tracing` spans across HTTP
  requests and Kafka events.
- **Context:** Traces connect the `POST /orders` → `order-placed` →
  `GET /orders/{id}` flow.
- **Dependencies:** P7-01, P7-02.
- **Scope:** `app` and `order-query` source and tests.
- **Implementation requirements:**
  - Add `Micrometer Tracing` and `Brave` bridge dependencies to both modules.
  - Configure `ObservationRegistry` and a `BraveTracer` with a no-op or
    logging-only reporter.
  - HTTP requests create a server span; `order-query` `RestTemplate` calls (if
    any) would create client spans, but cross-service calls are forbidden, so
    the only client spans are Kafka producers.
  - Kafka `ProducerListener` starts a producer span and injects `b3` or W3C
    trace headers into the record.
  - Kafka `RecordInterceptor` extracts the trace headers, creates a consumer
  span, and sets it as the current observation.
  - Logs include `traceId` and `spanId` via MDC.
- **Acceptance criteria:**
  - `POST /orders` logs in `app` and `order-query` share the same `traceId`.
  - A missing trace context results in a new root span.
  - `make verify` passes.
- **Verification requirements:** Run end-to-end trace tests; run `make verify`.
- **Expected files/components:**
  - `app/src/main/kotlin/.../shared/observability/TracingConfiguration.kt`
  - `order-query/src/main/kotlin/.../shared/observability/TracingConfiguration.kt`
  - `app/src/integrationTest/kotlin/.../observability/EndToEndTracingTest.kt`
- **Architecture impact:** Adds cross-cutting tracing context without changing
  business logic.
- **Out of scope:** Central trace collection backend; only local logs/metrics.

### P7-04 — SLO metrics and dashboards

- **Objective:** Expose and document SLO-aligned metrics for critical APIs and
  end-to-end lag.
- **Context:** Phase 2-5 already exposes HTTP timings and lag metrics; this
  task aligns and documents them as SLOs.
- **Dependencies:** P7-01.
- **Scope:** `app` and `order-query` metrics and documentation.
- **Implementation requirements:**
  - Ensure `http_server_requests_seconds` is tagged with `method`, `uri`,
    `outcome`, and `status` in both services.
  - Add or verify SLO gauges for `GET /orders/{id}` p95, `GET /orders` p95,
    and `POST /orders` success rate.
  - Add a Prometheus `RecordingRules`-style documentation (not a running
    Prometheus server) or a `Grafana` dashboard JSON in `docs/observability/`.
  - Document the SLO targets and where to scrape each service's `/actuator/prometheus`.
- **Acceptance criteria:**
  - `GET /orders/{id}` p95 is observable from `/actuator/prometheus`.
  - SLOs are documented with target, window, and source metric.
  - `make verify` passes.
- **Verification requirements:** Run SLO verification tests; run `make verify`.
- **Expected files/components:**
  - `docs/observability/slo-dashboard.json` (optional, dashboard-as-code)
  - `docs/observability/slos.md`
  - Updated `CatalogSloVerificationTest` or new `ObservabilitySloTest`
- **Architecture impact:** None; metrics and documentation only.
- **Out of scope:** Running Prometheus/Grafana in `compose.yaml` (deferred to
  Phase 8 or local runbooks).

### P7-05 — Logging alignment and operational runbooks

- **Objective:** Ensure both services emit the same structured log format and
  add runbooks for triage.
- **Context:** Consistent logs and runbooks are required before operators can
  use the new observability signals.
- **Dependencies:** P7-02, P7-03.
- **Scope:** Logging configuration and `docs/observability/`.
- **Implementation requirements:**
  - Align `logback-spring.xml` (or equivalent) in `app` and `order-query` so
    that both emit the same JSON fields.
  - Add runbooks for: high `outbox_relay_lag`, high
    `order_read_model_lag_seconds`, poison message in DLQ, Kafka broker down,
    and PostgreSQL down.
  - Each runbook links to the relevant metrics, log queries, and trace fields.
- **Acceptance criteria:**
  - Both services emit JSON logs with `traceId`, `spanId`, `correlationId`,
    `service`, `logger`, `level`, `message`, `timestamp`.
  - Runbooks exist for the five listed failure modes.
  - `make verify` passes.
- **Verification requirements:** Review runbooks; run `make verify`.
- **Expected files/components:**
  - `app/src/main/resources/logback-spring.xml`
  - `order-query/src/main/resources/logback-spring.xml`
  - `docs/observability/runbooks/*.md`
- **Architecture impact:** None; logging and documentation only.
- **Out of scope:** External log aggregation.

### P7-06 — End-to-end observability verification

- **Objective:** Prove that a single order can be traced end-to-end.
- **Context:** The ultimate test of the phase: `POST /orders` in `app` and
  `GET /orders/{id}` in `order-query` share a trace.
- **Dependencies:** P7-02 through P7-05.
- **Scope:** Integration tests in `app` and `order-query`.
- **Implementation requirements:**
  - Write an integration test that places an order, waits for it to be visible
    in `order-query`, and asserts that the `traceId` in the `app` `POST` log
    appears in the `order-query` `GET` log and in the `order-query` consumer
    log.
  - Use the existing `ServiceExtractionE2ETest` pattern but assert
    correlation/trace continuity.
- **Acceptance criteria:**
  - The test passes and `make verify` passes.
  - The trace links `POST /orders` → outbox relay → `order-placed` →
    `order-query` projection → `GET /orders/{id}`.
- **Verification requirements:** Run the end-to-end observability test; run
  `make verify`.
- **Expected files/components:**
  - `app/src/integrationTest/kotlin/.../observability/ObservabilityE2ETest.kt`
- **Architecture impact:** Test-only.
- **Out of scope:** Load or performance testing.

### P7-07 — Observability evidence and phase gate

- **Objective:** Capture the observability evidence and close the phase.
- **Context:** AGENTS.md requires evidence-backed claims.
- **Dependencies:** P7-01 through P7-06.
- **Scope:** `docs/bootcamp/evidence/p7-observability.md`.
- **Implementation requirements:**
  - The report documents each task: scenario, instrumentation added, observed
    trace, log sample, SLO metric, and runbook reference.
  - The report records any observability gaps found and how they were fixed.
- **Acceptance criteria:**
  - The evidence report exists and covers all experiments.
  - `make verify` passes.
- **Verification requirements:** Review the report; run `make verify`.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p7-observability.md`
- **Architecture impact:** None; documentation.
- **Out of scope:** Advancing to Phase 8.

### Dependency graph

```text
P7-01 ──> P7-02 ──┬──> P7-03 ──┬──> P7-06 ──┐
                  └──> P7-04 ───┤            ├──> P7-07
                  └──> P7-05 ───┘            │
```

### Suggested execution order

P7-01 → P7-02 → P7-03 → P7-04 → P7-05 → P7-06 → P7-07

---

## 19. Phase exit criteria

Phase 7 is complete only when all of the following are true:

1. All tasks P7-01 through P7-07 are implemented and verified.
2. `make verify` passes from a clean checkout with no manual intervention
   beyond JDK 21, Docker, and Make.
3. Correlation IDs propagate through HTTP, Kafka, and logs in both services.
4. Distributed traces link `POST /orders` → `order-placed` →
   `order-query` projection → `GET /orders/{id}`.
5. Both services emit structured JSON logs with `traceId`, `spanId`,
   `correlationId`, `service`, and `timestamp`.
6. Critical API SLOs (`GET /orders/{id}` p95 < 100ms, `GET /orders` p95 < 200ms,
   `POST /orders` error rate) are asserted and documented.
7. Operational runbooks for Kafka outage, PostgreSQL outage, poison messages,
   and high read-model/outbox lag are documented.
8. The evidence report `docs/bootcamp/evidence/p7-observability.md` documents
   every experiment, runbook, and gap found.
9. No forbidden technology (Kubernetes, API gateway, service mesh, Redis,
   event sourcing, central APM stack, log aggregation, chaos tooling) has been
   introduced.
10. Git diff is clean and no unrelated files are modified.
11. The phase review process has been passed before `current-phase.md` is
    updated to Phase 08.
