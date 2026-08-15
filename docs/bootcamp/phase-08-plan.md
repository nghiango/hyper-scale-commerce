# Phase 08 — Load Engineering

Status: **APPROVED**

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-07-plan.md`
- `docs/bootcamp/evidence/p7-observability.md`
- `docs/observability/slos.md`
- `docs/observability/runbooks/`
- `docs/bootcamp/phase-02-plan.md` and the Phase 2 performance evidence
- `docs/bootcamp/evidence/p5-service-extraction.md`
- ADR-0001, ADR-0002, and ADR-0006 through ADR-0013
- The existing Phase 7 implementation (build, Compose topology, source,
  tests, metrics, and configuration)

There is no separate `docs/bootcamp/phase-07.md`; the approved Phase 7 plan,
completion marker, implementation, and evidence are therefore the available
Phase 7 specification and verification record.

---

## 1. Phase objective

Establish a reproducible, external load-engineering capability for the actual
two-service platform, measure its capacity and bottlenecks, and verify the
constitution's load targets in a documented qualification environment:

- at least 10,000 concurrent virtual users under a representative workload,
- p95 below 200ms for the defined critical APIs,
- a 5x request-rate spike with bounded degradation and recovery,
- at least 99.9% successful requests during the measured steady-state and
  spike windows, and
- zero lost or duplicate business outcomes after the asynchronous flow drains.

The phase must first measure the unchanged Phase 7 system, then apply only
evidence-justified tuning. It must not claim production capacity beyond the
documented environment or introduce an orchestration platform, cache, search
engine, service mesh, or additional application service.

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. Modular Monolith
3. Performance Engineering
4. Event-Driven Architecture
5. CQRS
6. Service Extraction
7. Resilience Engineering
8. Observability
9. **Load Engineering** <- this phase
10. Chaos Engineering

Phase 2 measured Catalog endpoints inside a Spring Boot integration-test JVM
for five-second windows and up to 500 worker threads. That work established a
performance-measurement discipline, but it predates Kafka, CQRS, service
extraction, resilience instrumentation, and distributed tracing. It is not a
capacity result for the current system.

Phase 7 made HTTP requests, Kafka delivery, projection lag, resource pools,
and failures observable. The platform can now be driven as two real
deployables and measured across its complete request and event paths. Phase 8
uses those signals to find saturation points, distinguish load-generator
limits from system limits, tune only proven bottlenecks, and produce the
evidence required by the constitution before Chaos Engineering begins.

## 3. Starting architecture / state

| Item | Actual Phase 7 state |
|---|---|
| Deployables | `app` on port 8080 and `order-query` on port 8081; `contracts` is a shared library module |
| Application responsibilities | `app`: Catalog reads, `POST /orders`, transactional outbox relay, Inventory consumer; `order-query`: `OrderPlaced` projection and `GET /orders*` |
| Communication | Kafka topic `order-placed`, exclusively asynchronous across deployables; DLQ `order-placed-dlq` |
| Persistence | One PostgreSQL 16 instance with deployable-owned schemas: `catalog`, `order`, `inventory`, and `order_query` |
| Delivery model | Transactional outbox, at-least-once Kafka delivery, idempotent Inventory and Order query consumers, bounded retry and DLQ |
| Event topology | One Kafka broker and one partition by current Compose defaults; outbox relay polls every 1s and claims up to 100 rows |
| Service capacity defaults | Tomcat maximum 200 threads, accept count 100, maximum 10,000 connections; Hikari maximum pool size 20 in each deployable |
| Observability | Prometheus-format Actuator metrics, SLO gauges, structured JSON logs, correlation IDs, Micrometer Tracing + Brave, lag and event counters |
| Existing performance harness | In-process Kotlin/JDK `HttpClient` tests for Catalog and a short Order query check; no external mixed-workload harness |
| Existing evidence | Phase 2 Catalog measurements; Phase 5 10-user Order query check and approximately 1.5s end-to-end projection observation; Phase 7 instrumentation evidence |
| Local deployment | Docker Compose builds and runs both services, PostgreSQL, and Kafka; no resource limits and no load-generator service |
| Architecture documentation gap | `docs/architecture.md` still labels Phase 6 as current even though `current-phase.md`, the implementation, and evidence mark Phase 7 complete |
| SLO documentation gap | The approved Phase 7 plan requires `GET /orders` p95 below 200ms, while `docs/observability/slos.md` says below 300ms |

The SLO conflict must not be silently inherited. The constitution's final
critical-API target and the higher-precedence approved Phase 7 plan require
Phase 8 to use **p95 below 200ms**. P8-02 reconciles the lower-precedence SLO
document before load results are accepted.

## 4. Target architecture / state

The product topology remains unchanged. A test-only load plane drives and
observes the existing Compose deployment:

```text
                  external load plane (test only)
             +-----------------------------------+
             | k6 scenarios + result summaries  |
             | resource/metric snapshot scripts |
             +----------------+------------------+
                              |
                  +-----------+-----------+
                  |                       |
                  v                       v
             app :8080              order-query :8081
       Catalog + Order command        Order queries
                  |                       ^
                  v                       |
          order.outbox_events             |
                  |                       |
                  +----> Kafka -----------+
                           |
                     Inventory consumer
                  |
                  v
             PostgreSQL 16
```

Target state:

- A pinned k6 container executes smoke, baseline, 10,000-user, and 5x spike
  scenarios against the real HTTP ports.
- Workload definitions separate concurrency from arrival rate. The
  10,000-user test uses a closed virtual-user model with explicit think time;
  the 5x spike test uses an arrival-rate model derived from the accepted
  steady-state request rate.
- Test data, workload mix, ramp, duration, environment, warm-up, and pass/fail
  thresholds are versioned and reproducible.
- k6 results are correlated with existing HTTP, JVM, Hikari, Kafka, outbox,
  read-model lag, PostgreSQL, and container resource signals.
- Baseline and tuned results identify the first saturated resource and show
  before/after evidence for every configuration or query change.
- Phase evidence reconciles generated orders with the outbox, Kafka consumer
  effects, Inventory reservations, and Order read model after drain, proving
  zero lost or duplicate business outcomes.
- Long-running load verification is an explicit command and CI artifact, not
  part of the ordinary `make verify` feedback loop. A short load smoke test is
  available for routine verification.

## 5. Problems this phase addresses

- Existing performance numbers were generated inside test JVMs, not against
  the containerized two-deployable topology.
- Existing five-second tests are too short to expose pool exhaustion, JVM
  warm-up, garbage collection, outbox accumulation, or consumer lag.
- Concurrency and request rate are conflated in the Phase 2 harness; its
  reports label worker concurrency as RPS targets.
- There is no representative traffic mix spanning Catalog reads, Order
  commands, Order queries, and asynchronous projection visibility.
- There is no verified capacity result for 10,000 concurrent users.
- There is no system-level 5x spike experiment for the current topology.
- There is no automated reconciliation proving that accepted Order writes all
  reach the outbox, Inventory, and Order query read model under load.
- There is no resource-saturation report connecting latency to JVM, database,
  connection-pool, Kafka, outbox, and consumer-lag signals.
- Phase 7's documented Order-list SLO conflicts with its approved plan.
- The current architecture document is one phase behind the implementation.

## 6. Architecture changes

- **No product topology change.** `app`, `order-query`, Kafka, and PostgreSQL
  retain their Phase 7 responsibilities and ownership boundaries.
- Add a test-only `performance/` area containing k6 scenarios, shared workload
  code, deterministic data preparation, result summarization, and environment
  capture.
- Add explicit Make targets for load smoke, baseline, target verification, and
  spike verification.
- Add a Compose load-test profile or overlay for the pinned k6 runner. It must
  not be included in normal application startup and must not become a runtime
  dependency.
- Add test-only reconciliation queries or scripts that respect ownership:
  each deployable's owned schema is queried independently for verification;
  application code must not gain cross-schema access.
- Any production configuration, index, Kafka partition, relay, or consumer
  concurrency change is conditional on P8-04 evidence and must remain within
  existing bounded-context and deployable boundaries.
- A change to Kafka partitioning/ordering, database indexing strategy, or a
  major capacity mechanism requires an ADR before implementation.

## 7. Technology changes

### Introduced in this phase

- **k6, test-only, pinned container image.** k6 is introduced as the external
  HTTP load generator because it supports both virtual-user and arrival-rate
  models, threshold assertions, scenario composition, and machine-readable
  summaries without adding a library to either application deployable.
- **POSIX shell and existing HTTP/Docker interfaces** for orchestration,
  Actuator metric snapshots, container statistics, and evidence assembly.

The exact k6 image version and digest must be selected and recorded by
ADR-0014 in P8-01. Floating tags such as `latest` are forbidden.

### Retained

- Kotlin, Spring Boot, Gradle, PostgreSQL, Flyway, Kafka, Spring Kafka,
  Testcontainers, Spring Data JDBC, jOOQ, Micrometer, Brave, MDC, Docker, and
  Docker Compose.

### Explicitly deferred

- Prometheus/Grafana servers, central APM, and log aggregation. Existing
  Actuator endpoints, structured logs, k6 output, and bounded metric snapshots
  are sufficient to find the first capacity bottleneck in this phase.
- Kubernetes, an API gateway, service discovery, service mesh, autoscaling,
  and production load balancers.
- Redis or any other cache, Elasticsearch, read replicas, database sharding,
  separate physical databases, and additional Kafka brokers.
- Distributed load-generator infrastructure. A qualification run must reject
  results if one k6 generator saturates; a later ADR and approval are required
  before adding distributed generators.
- Event sourcing, synchronous inter-service calls, and additional extracted
  services.
- Network-partition and randomized fault injection, which belong to Phase 9
  (Chaos Engineering).

## 8. Non-functional requirements

- `make verify` continues to pass from a clean checkout.
- Load scripts pass lint/syntax checks and the short smoke scenario completes
  from a clean checkout with Docker and Make.
- Long-running tests do not execute implicitly as part of `make verify`.
- Every load result records commit SHA, dirty-worktree state, UTC timestamp,
  host CPU/memory, Docker version and allocation, OS, image digests, service
  JVM settings, data cardinality, scenario parameters, and test duration.
- Results from a dirty worktree are marked non-qualifying.
- Warm-up samples are excluded from threshold evaluation and the warm-up
  duration is recorded.
- Load-generator saturation is measured. A target result is invalid if the
  generator exhausts CPU/memory, reports dropped iterations, or cannot sustain
  its configured arrival rate.
- At least three qualifying repetitions of the final steady-state and spike
  scenarios are required; report the median and worst p95, throughput, error
  rate, and recovery time.
- Performance assertions use k6 end-to-end client latency. In-process
  Micrometer percentiles are supporting diagnostics, not substitutes.
- All tuning is evidence-backed with before/after numbers and a stated
  tradeoff. No speculative configuration changes are allowed.
- No secrets, customer data, or unbounded high-cardinality metric/log labels
  are introduced.

## 9. Performance expectations

### Critical API classification

The following are critical for Phase 8 and must each have p95 below 200ms in
the accepted steady-state windows:

| Deployable | Critical API | Rationale |
|---|---|---|
| `app` | `GET /catalog/products/{id}` | Primary product-detail read |
| `app` | `GET /catalog/products?page=0&size=20` | Primary catalog-browse read |
| `app` | `POST /orders` | Primary durable Order command |
| `order-query` | `GET /orders/{id}` | Primary Order detail read |
| `order-query` | `GET /orders?page=0&size=20` | Primary Order history read |

Catalog substring search remains measured but is not a Phase 8 critical API;
its known sequential scan is documented in Phase 2 evidence. A search-specific
target or new search technology requires separate requirements and an ADR.

### Workload expectations

- **Smoke:** small deterministic workload proving scripts, data, checks, and
  reconciliation before expensive runs.
- **Baseline:** a stepped test that discovers the knee of the unchanged Phase
  7 system without crossing host or generator safety limits.
- **Concurrent-user qualification:** at least 10,000 active k6 virtual users
  with explicit think time and the approved representative request mix. VUs
  must be simultaneously active during a steady-state window of at least 15
  minutes after warm-up.
- **5x spike qualification:** increase offered request rate from an accepted,
  sustainable steady-state rate to 5x that rate for at least 60 seconds, then
  return to baseline and observe recovery for at least five minutes.
- **Request success:** at least 99.9% of requests in the steady-state target
  window succeed. Expected polling `404` responses before projection
  visibility are tracked separately and must not be hidden from the report.
- **Async visibility:** at steady state, accepted orders reach the Order query
  read model with p95 no greater than 2 seconds. This budget is grounded in the
  existing 1-second relay interval and the approximately 1.5-second Phase 5
  observation; P8-04 must confirm or challenge it before tuning.
- **Spike behavior:** the spike may exceed the 200ms latency SLO, but the
  system must not exhibit unbounded backlog, process failure, or data loss.
  The report must show peak p95/p99, peak lag, rejected/error rate, and time to
  return to the accepted steady-state latency and lag bands.

The 10,000-user and 5x spike claims are independent: the former is a closed
concurrency model, while the latter is an open arrival-rate model. Neither may
be inferred from thread-pool size or a short throughput sample.

## 10. Reliability expectations

- Every successful `POST /orders` creates exactly one durable Order and one
  outbox event.
- After a bounded drain period, every accepted Order has exactly one logical
  Inventory reservation and one Order query read-model row. At-least-once
  physical delivery may create duplicate attempts, but idempotency must
  prevent duplicate business effects.
- No accepted Order or unpublished outbox event is deleted to make a load test
  pass.
- Outbox depth, oldest unpublished age, Kafka consumer lag, DLQ count, and
  read-model lag are captured before, during, and after each write workload.
- After the 5x spike, backlog and lag must trend down and return to the
  pre-spike steady-state band within the five-minute observation window. If
  they do not, the scenario fails and the sustainable baseline is revised.
- Both deployables remain alive and ready throughout steady-state
  qualification. Readiness loss, process restart, out-of-memory termination,
  or database/Kafka unavailability is a failed run.
- The measured request-success ratio is a load-window proxy for the 99.9%
  availability target; it is not a claim of calendar-month production
  availability.
- Existing retry, DLQ, outbox, tracing, and MDC behavior must not regress
  under concurrency.

## 11. Observability requirements

- k6 reports request count, active VUs, offered and achieved rate, p50, p95,
  p99, maximum latency, status distribution, error rate, checks, dropped
  iterations, and bytes transferred per scenario and endpoint group.
- Existing Actuator endpoints supply HTTP, JVM heap/non-heap, GC pause, live
  threads, Hikari active/idle/pending/acquire time, and process CPU signals.
- Existing domain metrics supply events published/consumed/DLQ, outbox relay
  lag, read-model lag, and SLO gauges.
- Container CPU, memory, network, block I/O, restart count, and health state are
  sampled for `app`, `order-query`, PostgreSQL, Kafka, and k6.
- PostgreSQL active connections, lock waits, slow queries, and query plans are
  captured when database saturation is suspected, using read-only diagnostics.
- Kafka partition count and consumer-group lag are captured before, during,
  and after write-heavy tests.
- Scenario names and endpoint groups remain bounded labels. Order IDs,
  correlation IDs, trace IDs, SKU values, and raw paths must not become metric
  labels.
- A small sampled set of correlation/trace IDs is retained for diagnosis; full
  per-request trace logging at 10,000-user load must be assessed for overhead
  and log volume without weakening correctness assertions.

## 12. Security considerations

- Load tests run only against an explicitly supplied allow-listed base URL;
  defaults point to localhost/Compose. The runner must refuse an unapproved
  non-local target.
- No production or shared environment may be load-tested without explicit
  authorization and an environment-specific capacity window.
- Test data is synthetic and contains no credentials, payment data, personal
  data, or customer identifiers.
- Secrets are provided through the existing environment mechanism and never
  written to k6 scripts, summaries, logs, or evidence.
- Load scripts cap maximum VUs, arrival rate, duration, and payload size to
  prevent accidental denial of service.
- Actuator exposure remains limited to the existing endpoints; this phase does
  not make management endpoints public.
- Error bodies and structured logs are reviewed to ensure load does not cause
  secrets or raw request bodies to be emitted.

## 13. Data considerations

- A deterministic, synthetic data set is prepared for Catalog and Order
  workloads. Data cardinality and seed are versioned with the scenario.
- Seed and cleanup operations are explicit, idempotent, and limited to a
  dedicated load-test database/Compose volume. They must refuse an unknown or
  non-local database target.
- Each service retains exclusive ownership of its schemas. Reconciliation
  tooling may read each schema for test assertions but must not be linked into
  application runtime code or mutate another deployable's schema.
- Order identifiers used for verification are captured from successful POST
  responses; reconciliation must not infer success solely from aggregate row
  counts.
- Read-model and Inventory duplicates are evaluated by business key, not by
  Kafka delivery attempt count.
- Data growth effects are measured: Order, outbox, read-model, and reservation
  row counts and relevant table/index sizes are recorded before and after.
- Production migrations are allowed only when profiling proves a missing or
  inefficient index. Each migration requires query-plan evidence and a rollback
  or mitigation note.
- Event payloads and the shared `contracts` module remain backward-compatible;
  no contract change is planned.

## 14. Explicitly out-of-scope capabilities

- Implementing Customer, Cart, Payment, Shipping, or Notification behavior to
  make a more elaborate workload.
- Changing business requirements or defining production traffic forecasts not
  present in repository requirements.
- Kubernetes, autoscaling, API gateway, service discovery, service mesh, or a
  production load balancer.
- New caches, search stores, read replicas, shards, separate physical
  databases, or additional Kafka brokers.
- Extracting another service or adding synchronous inter-service calls.
- Prometheus/Grafana deployment, central APM, log aggregation, alert routing,
  real-user monitoring, synthetic production probes, or continuous profiling.
- Distributed load generation unless a single validated generator cannot
  produce the approved workload; that requires a separate ADR and approval.
- Capacity planning for regions, multi-zone failover, cost modeling, or cloud
  instance selection.
- Network partitions, dependency termination during load, randomized faults,
  or chaos tooling (Phase 9).
- Claiming production-wide 99.9% availability from a bounded load test.
- Automatic progression to Phase 9.

## 15. Dependencies on the previous phase

Phase 8 depends on the completed Phase 7 state:

- P7-01 through P7-07 are implemented and the Phase 7 evidence report records
  passing exit criteria.
- `docs/bootcamp/current-phase.md` marks Phase 07 COMPLETE.
- `make verify` passes with the observability, resilience, integration,
  architecture, and quality suites.
- Both services expose structured logs, trace/correlation context, health, HTTP
  metrics, SLO gauges, and domain lag/counter metrics.
- The Compose `services` profile runs the real two-deployable topology.
- Phase 6 resilience mechanisms remain intact so accepted work can drain after
  a burst.

Before Phase 8 implementation begins:

- this plan must be approved,
- `docs/bootcamp/current-phase.md` must be advanced through the repository's
  phase-progression process, and
- the pre-existing dirty worktree must be resolved or explicitly excluded from
  qualification evidence.

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Load generator saturates before the system** | High | Capture k6 CPU/memory and dropped iterations; reject invalid runs; separate generator and SUT resources for qualification when available |
| **Local results are presented as production capacity** | High | Record exact environment and scope every claim; distinguish regression from qualification runs |
| **10,000 VUs are mistaken for 10,000 RPS** | High | Separate closed concurrency and open arrival-rate scenarios; report both configured and achieved rates |
| **Single Kafka partition or 1s/100-row relay becomes the write bottleneck** | High | Measure outbox depth and consumer lag first; require an ADR for partition/ordering changes; tune relay only with evidence |
| **Shared PostgreSQL causes cross-service contention** | High | Capture per-pool and database connection/lock/query signals; preserve schema ownership; do not jump to separate databases |
| **Tracing/logging volume distorts results** | Medium | Measure log volume and tracing overhead; keep correctness context; any sampling change must be explicit and verified |
| **OS connection/file-descriptor limits invalidate 10,000-user tests** | High | Preflight generator and SUT limits; record changes; fail fast rather than silently reducing concurrency |
| **Unbounded test data growth exhausts storage** | Medium | Use a dedicated load-test volume, bounded durations, preflight capacity, and explicit cleanup |
| **Expected eventual-consistency 404s hide real errors** | Medium | Tag polling separately; reconcile accepted IDs; report all status classes |
| **Existing SLO documentation conflict corrupts thresholds** | Medium | Reconcile in P8-02; use constitution and approved Phase 7 plan target of p95 below 200ms |
| **Tuning overfits one workstation** | Medium | Require three repetitions, document resources, retain defaults unless improvement is repeatable |
| **Long tests make routine verification impractical** | Low | Keep smoke and qualification commands separate; CI uploads results only for explicit load jobs |
| **Scope expands into autoscaling or caching** | High | Preserve the existing topology; stop and request an ADR/approval if evidence proves topology change is necessary |

## 17. ADRs that may be required

- **ADR-0014 — Load-test strategy and qualification model (required).** Record
  k6 selection and pinned image, external black-box testing, workload models,
  critical APIs, representative mix, environment qualification, result
  validity rules, and why the existing Kotlin harness is retained only for
  regression compatibility rather than Phase 8 capacity claims.
- **ADR-0015 — Capacity bottleneck remediation (conditional).** Required only
  if P8-04 justifies a major performance mechanism or changes Kafka partition
  count/ordering, outbox concurrency semantics, database indexing strategy, or
  another architectural capacity decision. Configuration-only tuning within
  accepted mechanisms does not automatically require this ADR, but must still
  have evidence.
- **Distributed load generation ADR (conditional, not pre-approved).** If a
  validated single k6 generator cannot create the qualification workload,
  stop and obtain approval before planning or introducing distributed load
  infrastructure.

## 18. Ordered implementation tasks

### P8-01 — ADR: load-test strategy and qualification model

- **Objective:** Record how load is generated, modeled, validated, and judged
  before adding the harness.
- **Context:** Phase 2 explicitly requires an ADR when an external load tool is
  adopted. Phase 8 introduces k6 and makes capacity claims against
  constitution targets.
- **Dependencies:** Phase 7 complete; this Phase 8 plan approved.
- **Scope:** Documentation only.
- **Implementation requirements:**
  - Create ADR-0014 covering k6 alternatives, exact pinned image strategy,
    black-box Compose testing, closed-VU and open-arrival-rate models, workload
    mix, environment tiers, generator-validity checks, pass/fail thresholds,
    raw-result retention, and long-test separation from `make verify`.
  - Define which results are local regression evidence and which qualify the
    constitution targets.
  - State that external/non-local targets require explicit authorization.
- **Acceptance criteria:**
  - ADR-0014 exists under `docs/adr/` and is accepted before harness
    implementation.
  - The chosen k6 version/image digest is fixed; no floating image tag exists.
  - The ADR maps each constitution load target to a concrete scenario and
    metric.
  - The ADR introduces no runtime dependency in `app` or `order-query`.
- **Verification requirements:** Architecture/document review against
  AGENTS.md, the constitution, ADR-0013, and this plan.
- **Expected files/components:**
  - `docs/adr/0014-load-test-strategy.md`
- **Architecture impact:** Adds a test-plane decision; product architecture is
  unchanged.
- **Out of scope:** k6 scripts, service tuning, and test execution.

### P8-02 — Workload contract, SLO reconciliation, and environment specification

- **Objective:** Define one reproducible workload and remove documentation
  ambiguity before collecting results.
- **Context:** The repository has no mixed production-like workload model, and
  the Order-list SLO conflicts between the approved Phase 7 plan and
  `docs/observability/slos.md`.
- **Dependencies:** P8-01.
- **Scope:** Performance and architecture documentation; no application source
  changes.
- **Implementation requirements:**
  - Define the critical API set from Section 9, request mix, synthetic user
    journeys, think-time distribution, data cardinality, warm-up, ramps,
    steady-state windows, spike shape, drain window, and random seed.
  - Define separate smoke, baseline, 10,000-user, and 5x spike profiles with
    bounded maximums.
  - Define expected statuses, including separate treatment of projection
    polling before visibility.
  - Reconcile `GET /orders` p95 to below 200ms in
    `docs/observability/slos.md`, recording why the former 300ms value was not
    authoritative for Phase 8.
  - Update `docs/architecture.md` to describe the completed Phase 7 state and
    test-only Phase 8 load plane without claiming Phase 8 completion.
  - Document qualification environment capture and invalid-run rules.
- **Acceptance criteria:**
  - Every scenario has explicit users/rate, ramp, duration, data, thresholds,
    and stop conditions.
  - Concurrency, offered rate, and achieved throughput are separately defined.
  - All critical APIs have p95 below 200ms thresholds.
  - The workload includes Catalog reads, Order commands, Order queries, and
    asynchronous visibility/reconciliation.
  - Architecture and SLO documentation no longer contradict the actual Phase
    7 state or this plan.
- **Verification requirements:** Document review; cross-check all endpoint
  paths against controllers and all metric names against Actuator output/tests.
- **Expected files/components:**
  - `performance/README.md`
  - `performance/workloads.md`
  - `docs/observability/slos.md`
  - `docs/architecture.md`
- **Architecture impact:** Documents the load plane and corrects stale state;
  no runtime change.
- **Out of scope:** Executable load scripts and tuning.

### P8-03 — External load harness and deterministic test data

- **Objective:** Implement the safe, repeatable harness that executes the
  approved workload against the real Compose services.
- **Context:** Existing in-JVM tests cannot validate the distributed topology
  or distinguish generator and service resource limits.
- **Dependencies:** P8-01, P8-02.
- **Scope:** Test-only performance assets, Compose profile/overlay, Make
  targets, and smoke verification.
- **Implementation requirements:**
  - Add shared k6 helpers and scripts for smoke, stepped baseline,
    concurrent-user qualification, and arrival-rate spike profiles.
  - Add deterministic data preparation and ID capture without changing
    production seed behavior.
  - Add an explicit base-URL allow list, maximum VU/rate/duration guards, and
    refusal of unauthorized remote targets.
  - Add a pinned k6 service in a Compose profile or overlay, isolated from
    normal `make up` and `make services` behavior.
  - Add `make load-smoke`, `make load-baseline`, `make load-verify`, and
    `make load-spike` (or equivalently clear targets).
  - Write raw results beneath ignored `build/performance-results/`; never
    overwrite committed evidence from routine tests.
  - Capture environment metadata, service health, metric snapshots, container
    stats, and Kafka consumer lag around a run.
  - Add reconciliation that checks accepted Order IDs against Order, outbox,
    Inventory, and Order query state after drain, using test-only read access.
- **Acceptance criteria:**
  - `make load-smoke` starts from the documented Compose state, executes all
    critical paths, reconciles accepted orders, and exits non-zero on a failed
    threshold/check.
  - Re-running with the same seed and empty load-test data produces the same
    workload composition.
  - The normal service profile contains no running k6 component.
  - Safety guards reject an unknown external target and out-of-range load
    parameters.
  - Raw summaries include the required client, generator, service, event, and
    resource measurements.
  - `make verify` passes and remains free of long load scenarios.
- **Verification requirements:**
  - Run k6 syntax/inspection checks.
  - Run `make load-smoke` twice from a clean load-test data set.
  - Run `make verify`.
  - Inspect Compose config to prove k6 is test-only and pinned.
- **Expected files/components:**
  - `performance/k6/`
  - `performance/scripts/`
  - `performance/compose.load.yml` or a load-only `compose.yaml` profile
  - `Makefile`
  - `.gitignore` if a more specific result path is needed
- **Architecture impact:** Adds external test tooling only.
- **Out of scope:** Production application tuning and target-scale claims.

### P8-04 — Untuned baseline, saturation curve, and bottleneck analysis

- **Objective:** Measure the unchanged Phase 7 system and identify the first
  real bottleneck before tuning.
- **Context:** Current pool, thread, relay, partition, and tracing settings have
  never been exercised under sustained system load.
- **Dependencies:** P8-03.
- **Scope:** Load execution, read-only diagnostics, and baseline evidence; no
  product behavior or tuning changes.
- **Implementation requirements:**
  - Execute at least three stepped baseline runs after warm-up, increasing load
    until the first SLO breach, resource saturation, backlog instability, or
    configured safety stop.
  - Exercise read-only, write-heavy, and representative mixed workloads so
    bottlenecks are attributable.
  - Capture k6 latency/rate/error data, HTTP metrics, JVM/GC, Hikari,
    PostgreSQL, Kafka, outbox, projection lag, DLQ, container resources, and
    generator headroom.
  - Capture query plans only for queries implicated by evidence.
  - Identify the saturation knee, first constrained resource, and causal chain
    for each workload; explicitly distinguish evidence from inference.
  - Record whether the 2-second steady-state projection budget is supported.
- **Acceptance criteria:**
  - Baseline evidence contains three comparable runs and full environment
    metadata.
  - The maximum sustainable offered/achieved rate and concurrency are reported
    for each workload.
  - The first SLO breach or saturation point is identified with at least two
    corroborating signals, or the report states that the configured safe limit
    was reached without saturation.
  - Generator saturation and invalid runs are explicitly reported and excluded.
  - No product source/configuration/migration change is included in this task.
- **Verification requirements:** Re-run one baseline point and confirm latency,
  throughput, and resource utilization are within a documented variance band;
  review raw summaries against the committed report.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p8-baseline.md`
  - ignored `build/performance-results/baseline/`
- **Architecture impact:** None; measurement only.
- **Out of scope:** Tuning, topology changes, and final target declaration.

### P8-05 — Evidence-guided capacity tuning

- **Objective:** Remove only the bottlenecks proven by P8-04 while preserving
  correctness, resilience, observability, and architecture boundaries.
- **Context:** Likely constraints include connection/thread pools, database
  queries, the 1-second/100-row outbox relay, Kafka partitioning, consumer
  concurrency, or observability overhead, but none may be assumed in advance.
- **Dependencies:** P8-04; ADR-0015 accepted first if the selected remediation
  meets Section 17's architectural threshold.
- **Scope:** The smallest configuration, query/index, or accepted-mechanism
  changes justified by baseline evidence, plus focused tests and documentation.
- **Implementation requirements:**
  - For every proposed change, record the baseline symptom, causal evidence,
    expected effect, tradeoff, rollback, and success threshold before editing.
  - Change one bottleneck class at a time and re-run the same load point.
  - Keep database access within service-owned schemas and preserve PostgreSQL
    as source of truth.
  - Preserve at-least-once delivery, idempotency, bounded retries, DLQ,
    correlation, tracing, and structured logging.
  - Add or update unit, integration, architecture, and performance regression
    tests appropriate to each change.
  - If no safe in-scope tuning materially improves the measured result,
    document that outcome and do not add speculative changes.
  - Stop and request approval if meeting the target requires an out-of-scope
    technology or topology change.
- **Acceptance criteria:**
  - Every change has comparable before/after results from at least three runs.
  - A retained change produces a repeatable material improvement in the target
    bottleneck or increases headroom without violating another SLO; otherwise
    it is reverted.
  - Correctness reconciliation passes with zero missing/duplicate business
    outcomes.
  - Existing resilience and observability tests pass.
  - `make verify` and `make load-smoke` pass.
  - Documentation describes final settings and operational tradeoffs.
- **Verification requirements:** Focused tests for changed components; three
  before/after load repetitions; `make verify`; `make load-smoke`; architecture
  review when ADR-0015 is required.
- **Expected files/components:** Evidence determines exact files. Possible
  areas include service configuration, owned-schema migrations/repositories,
  Kafka/outbox configuration, focused tests, ADR-0015, and
  `docs/bootcamp/evidence/p8-tuning.md`.
- **Architecture impact:** Conditional; must be documented before any major
  performance mechanism is introduced.
- **Out of scope:** Unmeasured tuning, new data stores, new deployables,
  orchestration, or weakened durability/observability.

### P8-06 — 10,000-concurrent-user qualification

- **Objective:** Verify the final tuned system against the constitution's
  concurrency, latency, success, and data-integrity targets.
- **Context:** Passing small regression tests does not demonstrate the
  10,000-user target.
- **Dependencies:** P8-05.
- **Scope:** Qualification execution and evidence; no tuning during accepted
  runs.
- **Implementation requirements:**
  - Start from a clean, recorded data/environment state and execute the
    approved 10,000-VU mixed workload for at least 15 steady-state minutes
    after warm-up.
  - Run at least three qualifying repetitions without changing code,
    configuration, resources, or data cardinality between runs.
  - Verify generator headroom and reject invalid runs.
  - Record per-critical-API p50/p95/p99, success/error/status counts, offered
    and achieved throughput, all Section 11 signals, and async visibility.
  - Drain and reconcile all successful Order commands after every repetition.
- **Acceptance criteria:**
  - At least 10,000 VUs are concurrently active throughout each accepted
    steady-state window.
  - Every critical API's worst accepted-run p95 is below 200ms.
  - Request success is at least 99.9% in every accepted steady-state window.
  - Steady-state Order projection visibility p95 is no greater than 2 seconds.
  - No service restarts/readiness loss, unbounded lag, DLQ growth from valid
    messages, or generator saturation occurs.
  - Reconciliation finds zero missing Orders/outbox effects, zero missing or
    duplicate Inventory business effects, and zero missing or duplicate Order
    read-model rows.
- **Verification requirements:** Three qualification runs; independent review
  of raw summaries and reconciliation; compare client latency with Actuator
  metrics and resource signals.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p8-10k-users.md`
  - ignored `build/performance-results/qualification/`
- **Architecture impact:** None; verification only.
- **Out of scope:** Further tuning within the same accepted run or extrapolating
  beyond the measured environment.

### P8-07 — 5x traffic-spike and recovery qualification

- **Objective:** Prove that the tuned platform absorbs or safely degrades
  during a fivefold request-rate spike and returns to its steady-state band
  without losing accepted work.
- **Context:** The constitution requires 5x traffic-spike behavior, and the
  asynchronous path can trade immediate throughput for bounded backlog only
  if recovery and integrity are measured.
- **Dependencies:** P8-06.
- **Scope:** Spike/recovery execution and evidence; no dependency failure or
  chaos injection.
- **Implementation requirements:**
  - Use the accepted sustainable arrival rate from P8-06/P8-04 as 1x; ramp to
    5x for at least 60 seconds; return to 1x and observe at least five minutes.
  - Run at least three qualifying repetitions with identical shape.
  - Capture offered versus achieved rate, latency/status/error distribution,
    pool/resource saturation, outbox depth/age, consumer lag, read-model lag,
    DLQ, readiness, and generator headroom through all stages.
  - Record whether overload is queued, rejected, or timed out; do not treat
    silent loss as graceful degradation.
  - Drain and reconcile every accepted Order ID after the observation window.
- **Acceptance criteria:**
  - The offered request rate reaches 5x for the defined spike window in every
    accepted run, with no generator saturation.
  - No process crash, restart, readiness loss, out-of-memory termination, or
    valid-message DLQ growth occurs.
  - Backlog and lag do not grow after load returns to 1x and return to their
    pre-spike steady-state bands within the five-minute recovery window.
  - The report includes spike p95/p99 and error rate; the 200ms p95 is not
    falsely claimed if overload exceeds it.
  - Reconciliation proves zero loss and zero duplicate business effects for
    accepted Order requests.
  - Post-recovery critical-API p95 is below 200ms and success is at least
    99.9% for the final stable minute.
- **Verification requirements:** Three spike runs; raw-summary and
  reconciliation review; confirm all service and generator time series cover
  warm-up, 1x, 5x, recovery, and drain.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p8-spike.md`
  - ignored `build/performance-results/spike/`
- **Architecture impact:** None; verification only.
- **Out of scope:** Dependency outages, network faults, randomized chaos, and
  autoscaling.

### P8-08 — Load-engineering evidence and phase gate

- **Objective:** Consolidate reproducible evidence, unresolved capacity limits,
  and phase-exit results without overstating production readiness.
- **Context:** AGENTS.md requires evidence for every engineering claim and a
  complete diff review before completion.
- **Dependencies:** P8-01 through P8-07.
- **Scope:** Documentation, final verification, and phase review inputs.
- **Implementation requirements:**
  - Create the Phase 8 evidence report linking ADR, workload contract, exact
    commands, environment, baseline, bottleneck analysis, tuning, 10,000-user
    results, spike/recovery results, reconciliation, and raw artifact paths.
  - Record all failed/invalid runs and why they were excluded; do not retain
    only favorable results.
  - State measured capacity boundaries and the exact environment to which they
    apply.
  - Record unresolved bottlenecks, deferred technologies, and triggers for
    future ADRs.
  - Inspect git status and the complete staged/unstaged/untracked diff; confirm
    generated load artifacts and secrets are not committed.
- **Acceptance criteria:**
  - One evidence index makes every Phase 8 claim traceable to reproducible raw
    results and committed configuration.
  - All P8-01 through P8-07 acceptance and verification criteria are accounted
    for with PASS/FAIL and evidence.
  - `make verify` and `make load-smoke` pass from a clean checkout.
  - The three 10,000-user and three 5x-spike runs meet their acceptance
    criteria; otherwise Phase 8 remains incomplete.
  - No forbidden/future-phase technology or unrelated change is present.
  - The phase-review process passes before Phase 9 is planned or
    `current-phase.md` is advanced.
- **Verification requirements:** Run `make verify`; run `make load-smoke`;
  validate links and raw summaries; architecture review; phase review; full
  git diff and secret scan.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p8-load-engineering.md`
  - updates to performance documentation if final commands/settings differ
- **Architecture impact:** None; evidence and gate only.
- **Out of scope:** Advancing to or implementing Phase 9.

### Dependency graph

```text
P8-01 --> P8-02 --> P8-03 --> P8-04 --> P8-05 --> P8-06 --> P8-07 --> P8-08
```

The order is intentionally linear. Workload definitions must be stable before
the harness, the harness before the baseline, evidence before tuning, and the
steady-state target before the 5x rate is derived.

### Required execution order

P8-01 -> P8-02 -> P8-03 -> P8-04 -> P8-05 -> P8-06 -> P8-07 -> P8-08

Do not start P8-05 while P8-04 is still collecting evidence. Do not change
code, configuration, resources, or data between accepted repetitions in P8-06
or P8-07.

## 19. Phase exit criteria

Phase 8 is complete only when all of the following are true:

1. P8-01 through P8-08 are implemented in order and independently verified.
2. ADR-0014 is accepted; any conditionally required ADR is accepted before its
   implementation.
3. The external, pinned k6 harness safely exercises the real Compose
   deployables and the smoke test is reproducible.
4. Workload mix, data, environment, concurrency, arrival rate, warm-up,
   duration, thresholds, and invalid-run rules are versioned and documented.
5. The stale architecture state and Phase 7 Order-list SLO conflict are
   reconciled, with all Phase 8 critical APIs governed by p95 below 200ms.
6. An untuned saturation curve and bottleneck report exist for read-only,
   write-heavy, and representative mixed workloads.
7. Every retained tuning change has repeatable before/after evidence, tests,
   tradeoffs, and rollback guidance; no speculative tuning remains.
8. Three qualifying 10,000-concurrent-user runs complete with every critical
   API below 200ms p95, at least 99.9% request success, valid generator
   headroom, bounded lag, and no service readiness loss.
9. Three qualifying 5x arrival-rate spike runs complete without process loss
   or unbounded backlog, return to the pre-spike latency/lag bands within five
   minutes, and restore below-200ms p95 with at least 99.9% success.
10. Reconciliation proves zero missing or duplicate business outcomes for all
    accepted Orders in every qualification and spike run.
11. The measured environment and capacity boundary are explicit; the evidence
    does not claim broader production capacity or monthly availability.
12. Existing outbox, idempotency, bounded retry, DLQ, service ownership,
    tracing, correlation, structured logging, and SLO metrics remain intact.
13. `make verify` and `make load-smoke` pass from a clean checkout; long tests
    remain opt-in.
14. The evidence report records successful, failed, and invalid runs and links
    each claim to raw results.
15. No forbidden technology, secret, generated result bundle, or unrelated
    change is committed.
16. Architecture review and phase review pass before Phase 9 progression.

If the existing topology cannot meet criteria 8 or 9, Phase 8 remains open.
The evidence must identify the blocking capacity constraint, and any expansion
into deferred technology or topology requires a separately approved ADR and
revised plan rather than silent scope growth.
