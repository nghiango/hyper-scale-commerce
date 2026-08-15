# ADR-0013: Observability Strategy for the Two-Service Platform

- Status: Accepted
- Date: 2026-08-15
- Phase: 7 — Observability

## Context

`docs/constitution.md` defines the engineering evolution and stage 8 is
Observability. Phase 6 (ADR-0012) proved that the platform tolerates Kafka,
PostgreSQL, and partial service outages with zero intentional data loss and
bounded retries. The platform now has two deployables (`app` and `order-query`)
communicating exclusively through Kafka (`order-placed`) and sharing one
PostgreSQL instance with per-service schemas.

The constitution's distributed-systems rules (§5) require failures to be
observable through metrics and logs. The reliability rules (§7) require
end-to-end distributed tracing, correlation IDs, and operational observability.

Phase 6 explicitly deferred distributed tracing and operational observability
to Phase 7 so that resilience mechanisms could be proven first. What is missing
is the ability to correlate a `POST /orders` in `app` with the resulting
`GET /orders/{id}` in `order-query`, to trace the flow through the outbox,
Kafka, and the projection, and to document SLOs and runbooks. Phase 7 must
choose a tracing and observability approach that is lightweight, does not
introduce new runtime infrastructure, and can be verified through logs and
existing metrics.

## Alternatives Considered

1. **Micrometer Tracing with the Brave bridge (chosen).**
   Add `Micrometer Tracing` and the `Brave` bridge to both `app` and
   `order-query`. This gives the project a vendor-neutral tracing abstraction,
   `traceId`/`spanId` generation, and `B3`/`W3C` context propagation without
   requiring a remote collector. Spans are emitted through the existing
   `Observation` API and can be printed to logs. The runtime stack stays
   Spring-native and no new infrastructure is introduced. This is the smallest
   change that satisfies the constitution's tracing and correlation
   requirements.

2. **OpenTelemetry SDK with manual instrumentation.**
   OpenTelemetry is the emerging standard for traces, metrics, and logs. It
   would provide richer context propagation and is compatible with many
   backends. Rejected for this phase: it is a larger API surface, requires
   more configuration, and the value of a remote collector is not needed
   until a central APM backend is introduced. Revisit once a collector is
   justified.

3. **Spring Cloud Sleuth.**
   Sleuth historically provided distributed tracing for Spring. Rejected:
   the project has moved to Micrometer Tracing, and the version targets for
   the platform do not include Sleuth.

4. **Introduce a central APM backend now (Zipkin, Jaeger, Tempo).**
   A remote trace collector and query UI would make traces directly
   browsable. Rejected for this phase: it adds new runtime infrastructure,
   changes the local `compose.yaml` topology, and is not required to prove
   that traces and correlation IDs flow end-to-end. Central APM and log
   aggregation are deferred to Phase 8.

5. **Correlation IDs only, no distributed tracing.**
   Generate and propagate a `correlation-id` without `traceId`/`spanId`.
   Rejected: it satisfies correlation but not the constitution's explicit
   requirement for end-to-end distributed tracing. It also misses the
   parent/child relationship between `POST /orders` and the Kafka consumer
   span.

## Decision

Adopt the following observability strategy for Phase 7:

- **Keep the runtime infrastructure unchanged.** No new servers, sidecars, or
  collectors are introduced in `compose.yaml` or production. Observability is
  delivered by in-process instrumentation and the existing `/actuator/prometheus`
  and logging outputs.
- **Correlation IDs first.** Every HTTP request and Kafka record carries a
  `correlation-id`. The ID is generated at the first ingress point, propagated
  through HTTP headers and Kafka record headers, and written to every log line
  via SLF4J MDC.
- **Distributed tracing with Micrometer Tracing + Brave.** HTTP requests and
  Kafka producer/consumer operations create spans. The `Brave` bridge injects
  and extracts trace context through Kafka record headers. No remote reporter
  is configured; trace/span IDs are emitted through structured logs and can be
  used in `grep` and local analysis.
- **Structured JSON logs with MDC.** Both services emit the same JSON log
  format with `traceId`, `spanId`, `correlationId`, `service`, `logger`,
  `level`, `message`, and `timestamp`.
- **SLO-aligned metrics and runbooks.** Existing `http_server_requests` and lag
  metrics are documented as SLOs. Operational runbooks link symptoms
  (high lag, DLQ increments, dependency health) to the relevant logs, metrics,
  and trace fields.
- **Defer to later phases:** central APM backends, log aggregation platforms,
  real-user monitoring, synthetic probes, continuous profiling, and alerting
  routing infrastructure.

## Operational Cost

- Two new test and runtime dependencies: `Micrometer Tracing` and the `Brave`
  bridge, added to both `app` and `order-query` modules.
- HTTP filters, Kafka `ProducerListener`/`RecordInterceptor`, and MDC
tee
  management in `app` and `order-query`.
- New integration tests that assert correlation and trace continuity.
- Runbooks under `docs/observability/` that must be kept in sync with
  operational behavior.

## Failure Modes

- **Missing trace context:** a request or message without a trace header
  receives a new root span; the flow is still traceable.
- **MDC context leakage:** a thread pool, web request, or Kafka consumer fails
  to clear MDC. Mitigated by `OncePerRequestFilter`, `TaskDecorator`, and
  consumer post-processing hooks.
- **Kafka header conflict:** trace headers collide with existing `version` and
  `correlation-id` headers. Mitigated by using standard `b3`/`traceparent` keys
  and a dedicated `correlation-id` key.
- **Tracing latency overhead:** span creation adds CPU and memory overhead.
  Mitigated by using the no-op/ logging Brave reporter and measuring p95
  impact in SLO tests.
- **No collector available:** traces are only in logs for this phase; operators
  must use `grep` and `jq` to reconstruct flows until a collector is added.

## Consequences

- P7-02 implements correlation ID generation and propagation.
- P7-03 adds Micrometer Tracing/Brave and Kafka trace propagation.
- P7-04 aligns SLOs with existing HTTP and lag metrics.
- P7-05 standardizes structured logging and creates runbooks.
- P7-06 proves end-to-end trace continuity in an integration test.
- P7-07 captures the observability evidence.
- No new runtime infrastructure is introduced, preserving the Phase 6
  architecture.
- Central APM, log aggregation, and alerting infrastructure remain explicit
  Phase 8 concerns.
