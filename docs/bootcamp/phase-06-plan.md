# Phase 06 — Resilience Engineering

Status: **DRAFT** (pending approval)

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-05-plan.md`
- `docs/bootcamp/evidence/p5-service-extraction.md`
- `docs/adr/0006-kafka-event-broker.md`
- `docs/adr/0007-transactional-outbox.md`
- `docs/adr/0008-cqrs-order-query-side.md`
- `docs/adr/0010-extract-order-query-service.md`
- `docs/adr/0011-monorepo-module-data-ownership.md`
- The existing Phase 5 implementation (build, source, tests, evidence)

---

## 1. Phase objective

Prove that the two-deployable platform built in Phase 5 is resilient: it
tolerates dependency, infrastructure, network, and data failures with zero
intentional data loss, bounded retries, observable failures, and documented
recovery procedures. The phase produces failure experiments, fixes any
resilience gaps they expose, and captures evidence that the system recovers
and catches up when dependencies return.

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. Modular Monolith
3. Performance Engineering
4. Event-Driven Architecture
5. CQRS
6. Service Extraction
7. **Resilience Engineering** ← this phase
8. Observability
9. Load Engineering
10. Chaos Engineering

Phase 5 proved independent deployment, per-service data ownership, and
cross-service event flow. The platform is now two deployables communicating
through Kafka, but its behavior under failure is unproven. The constitution's
distributed-systems rules (§5) require durable messages, idempotent consumers,
observable failures, bounded retries, and poison-message handling. Phase 3-5
implemented these mechanisms (outbox, DLQ, retries, idempotent projection);
Phase 6 must verify them under real failure conditions and harden any gaps.

## 3. Starting architecture / state

| Item | State |
|---|---|
| Deployables | `app` (monolith, port 8080) and `order-query` (service, port 8081); `contracts` module shared |
| Communication | Kafka topic `order-placed`; DLQ `order-placed-dlq`; transactional outbox `order.outbox_events` with 1s relay |
| Consumers | `inventory` (in `app`) and `order-query` (projection); both idempotent with bounded retries and DLQ routing |
| Database | One PostgreSQL 16 instance; schemas `catalog`, `order`, `inventory` (app) and `order_query` (order-query); per-service Flyway |
| Resilience mechanisms | Outbox durability, at-least-once delivery, idempotent consumers, bounded retries, DLQ, startup-order independence (P5-07) |
| Tests | Testcontainers (PostgreSQL + Kafka); `ServiceExtractionE2ETest`; `InventoryFailureTest` (poison message → DLQ); `CatalogSloVerificationTest` |
| Evidence | `p5-service-extraction.md` (topology, flow, timings, idempotency, startup order, p95, rebuild procedure) |
| Docs | ADR-0006, 0007, 0008, 0010, 0011 |

## 4. Target architecture / state

The architecture is unchanged in shape. The target is verified, hardened
behavior:

```text
app (monolith)                                order-query (service)
  POST /orders                                  GET /orders/{id}
  GET /catalog/products*                        GET /orders?page=&size=
  Inventory consumer                            OrderPlaced projection
  outbox relay                                  (group order-query)
        |                                              ^
        v                                              |
  order.outbox_events ──relay──> Kafka order-placed ───┘
                                     |
                                     └──> group: inventory (unchanged, in app)
```

- Both services start and become healthy when PostgreSQL or Kafka is
  unavailable, and recover without manual intervention when the dependency
  returns.
- No events are lost during broker or database outages; the outbox buffers
  unpublished events and the projection catches up from the durable topic.
- Poison messages from either consumer land in `order-placed-dlq` and are
  observable via metrics.
- Failure behavior is covered by automated experiments in `make verify` and
  documented in `docs/bootcamp/evidence/p6-resilience.md`.

## 5. Problems this phase addresses

- No evidence of behavior under dependency failure (Kafka down, PostgreSQL
  down, partial outages).
- Consumer retry/backoff and DLQ behavior are implemented but only partially
  verified (Inventory poison message; order-query projection not covered).
- No documented recovery procedure for broker or database outages (the read
  model rebuild procedure exists; dependency recovery does not).
- No failure-injection harness; resilience claims cannot be reproduced.
- Startup-order independence is proven, but dependency-failure independence is
  not.

## 6. Architecture changes

- No runtime topology change: the two-deployable, event-only architecture from
  Phase 5 is preserved.
- Add a test-only failure-injection harness built on Testcontainers lifecycle
  control (stop/start PostgreSQL and Kafka containers) with wait-for-recovery
  helpers.
- Harden consumer configuration where experiments expose gaps (retry counts,
  backoff, DLQ routing) without changing business logic.
- Add resilience assertions to existing metric coverage
  (`events_dlq_total`, `outbox_relay_lag`, `order_read_model_lag_seconds`).

## 7. Technology changes

- **No new runtime technology.** The runtime stack is unchanged: Kotlin,
  Spring Boot, Kafka, PostgreSQL, Flyway, jOOQ.
- **Test-only additions:** Testcontainers lifecycle control (already an allowed
  technology) for stopping/starting dependency containers.
- **Explicitly deferred:** network-partition tooling (e.g., Toxiproxy) and
  chaos-engineering tooling are deferred to Phase 10 (Chaos Engineering).
  Circuit breakers and service mesh are deferred to later phases.

## 8. Non-functional requirements

- `make verify` passes from a clean checkout and includes the new failure
  experiments.
- All new Kotlin code passes `spotlessCheck` and `detekt`.
- Failure experiments are deterministic and bounded in runtime (no long
  sleeps; recovery waits use polling with deadlines).
- No intentional data loss: outbox events survive PostgreSQL restart; Kafka
  events survive broker restart.
- Services recover without manual intervention after dependency recovery.
- Existing Phase 2-5 tests continue to pass; no regressions.

## 9. Performance expectations

Phase 6 does not claim new performance targets. Existing expectations carry
over and must not regress:

- `GET /orders/{id}` p95 under 100ms at 100 concurrent requests.
- `GET /orders` p95 under 200ms at 100 concurrent requests.
- Catalog SLOs from Phase 2 remain green.
- `POST /orders` behavior must not regress.

## 10. Reliability expectations

- **Kafka outage:** the outbox buffers events; publishing resumes when the
  broker returns; consumers retry with backoff and catch up; no events lost.
- **PostgreSQL outage:** the affected service reports degraded health and
  recovers when the database returns; committed outbox rows are not lost.
- **Consumer failure:** bounded retries; poison messages routed to
  `order-placed-dlq`; the consumer continues processing subsequent messages.
- **Partial outage:** `app` down while `order-query` runs (and vice versa);
  the surviving service keeps serving; the down service catches up on restart.
- **Idempotency:** replaying events never duplicates or corrupts the read
  model or inventory reservations.

## 11. Observability requirements

- Existing metrics remain and are asserted in failure experiments:
  - `events_published_total{topic="order-placed"}` (app)
  - `events_consumed_total{consumer="order-query"|"inventory",outcome="processed"|"failed"}`
  - `events_dlq_total` (both consumers)
  - `outbox_relay_lag` (app)
  - `order_read_model_lag_seconds` (order-query)
- Actuator health endpoints reflect dependency state (degraded/UP) during
  outages where Spring Boot supports it.
- Structured JSON logging is preserved in both services.

## 12. Security considerations

- No new authentication or authorization surface is introduced.
- Failure experiments run only in tests against Testcontainers; no production
  infrastructure is touched.
- Logs must not include full stack traces for expected client errors.
- No secrets are introduced.

## 13. Data considerations

- No schema changes are planned; the write model remains the source of truth.
- The read model remains rebuildable by replaying the topic (procedure
  documented in `p5-service-extraction.md`).
- Outbox rows are the durability guarantee for events; experiments verify they
  survive database restarts.
- One PostgreSQL instance with per-service schemas; ownership boundaries are
  unchanged.

## 14. Explicitly out-of-scope capabilities

- Chaos engineering and network-partition tooling (Phase 10).
- Observability platform work (dashboards, tracing) (Phase 8).
- Load engineering (Phase 9).
- Circuit breakers, service mesh, API gateway, Redis, Kubernetes.
- Changes to Catalog, Order command, or Inventory business logic.
- New runtime infrastructure.

## 15. Dependencies on the previous phase

Phase 6 depends on the successful completion of Phase 5:

- P5-01 through P5-08 are complete and verified, and the Phase 5 phase-review
  has passed.
- `make verify` passes from a clean checkout.
- Two deployables exist (`app`, `order-query`) with per-service Flyway, jOOQ,
  Kafka consumers, DLQ, and actuator endpoints.
- `docs/bootcamp/current-phase.md` is advanced to Phase 06 before
  implementation begins.

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Flaky failure experiments** (container stop/start timing) | Medium | Polling with deadlines; deterministic wait-for-recovery helpers; bounded retries |
| **Slow tests** (container restarts add time) | Medium | Reuse shared containers per test class; keep experiments focused; parallelize where safe |
| **Retry/backoff tuning** causes long recovery times | Medium | Assert recovery within defined deadlines; tune backoff to bounded values |
| **Hidden coupling** exposed by failures | Medium | Fix at the smallest scope; record findings in the evidence report |
| **Scope creep toward chaos tooling** | Medium | Explicitly deferred (§14); Testcontainers lifecycle control is the failure-injection mechanism for this phase |

## 17. ADRs that may be required

- **ADR-0012 — Resilience strategy for the two-service platform.** Required:
  documents the resilience mechanisms (outbox durability, at-least-once
  delivery, idempotent consumers, bounded retries, DLQ, recovery procedures),
  the decision to defer circuit breakers and chaos tooling to later phases,
  and the failure-injection approach (Testcontainers lifecycle control).

## 18. Ordered implementation tasks

### P6-01 — ADR: resilience strategy

- **Objective:** Record the resilience approach before failure experiments are
  written.
- **Context:** AGENTS.md requires ADRs for significant architectural decisions;
  the resilience strategy is a cross-cutting decision.
- **Dependencies:** Phase 5 complete, phase-review passed.
- **Scope:** Documentation only.
- **Implementation requirements:**
  - Create ADR-0012 covering: the resilience mechanisms already in place
    (outbox, at-least-once, idempotent consumers, bounded retries, DLQ),
    recovery procedures, the failure-injection approach (Testcontainers
    lifecycle control), and the explicit deferral of circuit breakers and
    chaos tooling.
- **Acceptance criteria:**
  - ADR-0012 exists under `docs/adr/` and is accepted.
  - No new runtime technology is introduced.
- **Verification requirements:** Document review; no automated tests.
- **Expected files/components:**
  - `docs/adr/0012-resilience-strategy.md`
- **Architecture impact:** Records the phase's resilience decisions.
- **Out of scope:** Implementation.

### P6-02 — Failure-injection test harness

- **Objective:** Provide reusable Testcontainers helpers to stop/start
  dependency containers and wait for recovery.
- **Context:** Failure experiments need a deterministic way to take PostgreSQL
  and Kafka down and bring them back.
- **Dependencies:** P6-01.
- **Scope:** Test-only infrastructure in `app` and `order-query` integration
  test source sets.
- **Implementation requirements:**
  - Add helpers to stop and start the shared PostgreSQL and Kafka containers
    (Testcontainers `stop()`/`start()`).
  - Add wait-for-recovery helpers (poll readiness/health with deadlines).
  - Add an evidence writer for `docs/bootcamp/evidence/p6-resilience.md`.
- **Acceptance criteria:**
  - Helpers compile and are used by at least one experiment.
  - `make verify` passes.
- **Verification requirements:** Run the harness's first experiment; run
  `make verify`.
- **Expected files/components:**
  - Test helpers under `app/src/integrationTest/kotlin/.../resilience/`
  - Test helpers under `order-query/src/integrationTest/kotlin/.../resilience/`
- **Architecture impact:** None; test-only.
- **Out of scope:** Runtime changes.

### P6-03 — Kafka outage experiments

- **Objective:** Prove both services tolerate a Kafka outage at startup and
  during operation, and catch up when the broker returns.
- **Context:** Kafka is the event backbone; broker failure is the highest-risk
  dependency failure.
- **Dependencies:** P6-02.
- **Scope:** Integration tests in `app` and `order-query`.
- **Implementation requirements:**
  - Experiment A: start `order-query` with Kafka down; verify it becomes
    healthy and serves an empty/stale read model; start Kafka; verify the
    consumer connects and catches up.
  - Experiment B: with both services running, stop Kafka; verify the app's
    outbox buffers events (no loss); restart Kafka; verify the relay publishes
    and the projection catches up.
  - Assert `events_dlq_total`/`events_consumed_total` where applicable.
- **Acceptance criteria:**
  - No events are lost during the outage.
  - Both services recover without manual intervention.
  - `make verify` passes.
- **Verification requirements:** Run the Kafka outage tests; run `make verify`.
- **Expected files/components:**
  - `app/src/integrationTest/kotlin/.../resilience/KafkaOutageIntegrationTest.kt`
  - `order-query/src/integrationTest/kotlin/.../resilience/KafkaOutageIntegrationTest.kt`
- **Architecture impact:** None; verification.
- **Out of scope:** Network partitions (Phase 10).

### P6-04 — PostgreSQL outage experiments

- **Objective:** Prove the services tolerate a database outage and recover
  without data loss.
- **Context:** PostgreSQL is the source of truth; its failure must not lose
  committed data.
- **Dependencies:** P6-02.
- **Scope:** Integration tests in `app` and `order-query`.
- **Implementation requirements:**
  - Experiment A: stop PostgreSQL while `app` is running; verify the app
    reports degraded health and does not accept writes; restart PostgreSQL;
    verify recovery and that committed outbox rows are still present and
    published.
  - Experiment B: stop PostgreSQL while `order-query` is running; verify
    degraded health and stale reads (or failure) are observable; restart;
    verify recovery and catch-up.
- **Acceptance criteria:**
  - No committed data is lost across the outage.
  - Both services recover without manual intervention.
  - `make verify` passes.
- **Verification requirements:** Run the PostgreSQL outage tests; run
  `make verify`.
- **Expected files/components:**
  - `app/src/integrationTest/kotlin/.../resilience/PostgresOutageIntegrationTest.kt`
  - `order-query/src/integrationTest/kotlin/.../resilience/PostgresOutageIntegrationTest.kt`
- **Architecture impact:** None; verification.
- **Out of scope:** Database replication/failover (later phases).

### P6-05 — Consumer resilience: poison messages and bounded retries

- **Objective:** Verify the `order-query` projection consumer handles poison
  messages and bounded retries exactly like the Inventory consumer.
- **Context:** `InventoryFailureTest` covers the Inventory consumer; the
  extracted projection consumer needs the same coverage.
- **Dependencies:** P6-02.
- **Scope:** `order-query` integration tests (and `app` if shared helpers are
  reused).
- **Implementation requirements:**
  - Publish a malformed `OrderPlaced` payload to `order-placed`.
  - Verify bounded retries occur and the message lands in `order-placed-dlq`.
  - Verify subsequent valid messages are still processed.
  - Assert `events_dlq_total` and `events_consumed_total{outcome="failed"}`.
- **Acceptance criteria:**
  - The poison message is in the DLQ after bounded retries.
  - The consumer continues processing valid messages.
  - `make verify` passes.
- **Verification requirements:** Run the consumer resilience test; run
  `make verify`.
- **Expected files/components:**
  - `order-query/src/integrationTest/kotlin/.../resilience/ProjectionConsumerFailureTest.kt`
- **Architecture impact:** None; verification.
- **Out of scope:** Exactly-once delivery (forbidden until later phases).

### P6-06 — Partial-outage matrix

- **Objective:** Prove each service survives the other being down and catches
  up on restart.
- **Context:** Phase 5 proved startup-order independence; this task proves
  partial-outage independence.
- **Dependencies:** P6-02.
- **Scope:** `app` integration tests (boots both applications in one JVM).
- **Implementation requirements:**
  - Scenario A: `order-query` down while `app` accepts orders; verify orders
    are written and outbox events are published; start `order-query`; verify
    the projection catches up.
  - Scenario B: `app` down while `order-query` serves reads; verify the read
    model remains queryable; restart `app`; verify new orders flow.
- **Acceptance criteria:**
  - No data loss in either scenario.
  - The down service catches up on restart.
  - `make verify` passes.
- **Verification requirements:** Run the partial-outage test; run `make verify`.
- **Expected files/components:**
  - `app/src/integrationTest/kotlin/.../resilience/PartialOutageIntegrationTest.kt`
- **Architecture impact:** None; verification.
- **Out of scope:** Multi-node deployments (later phases).

### P6-07 — Resilience evidence and phase gate

- **Objective:** Capture the resilience evidence and close the phase.
- **Context:** AGENTS.md requires evidence-backed claims.
- **Dependencies:** P6-03 through P6-06.
- **Scope:** `docs/bootcamp/evidence/p6-resilience.md`.
- **Implementation requirements:**
  - The report documents each experiment: scenario, outage duration, observed
    behavior, recovery time, data-loss check, and metrics.
  - The report documents the recovery procedures for Kafka and PostgreSQL
    outages.
  - The report records any resilience gaps found and how they were fixed.
- **Acceptance criteria:**
  - The evidence report exists and covers all experiments.
  - `make verify` passes.
- **Verification requirements:** Review the report; run `make verify`.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p6-resilience.md`
- **Architecture impact:** None; documentation.
- **Out of scope:** Advancing to Phase 7.

### Dependency graph

```text
P6-01 ──> P6-02 ──> P6-03 ──┐
                └──> P6-04 ──┼──> P6-07
                └──> P6-05 ──┤
                └──> P6-06 ──┘
```

### Suggested execution order

P6-01 → P6-02 → P6-03 → P6-04 → P6-05 → P6-06 → P6-07

---

## 19. Phase exit criteria

Phase 6 is complete only when all of the following are true:

1. All tasks P6-01 through P6-07 are implemented and verified.
2. `make verify` passes from a clean checkout with no manual intervention
   beyond JDK 21, Docker, and Make.
3. Kafka outage experiments prove no event loss and automatic catch-up.
4. PostgreSQL outage experiments prove no committed data loss and automatic
   recovery.
5. The `order-query` projection consumer routes poison messages to
   `order-placed-dlq` after bounded retries and continues processing.
6. Partial-outage scenarios (each service down while the other runs) prove no
   data loss and catch-up on restart.
7. Resilience metrics (`events_dlq_total`, `events_consumed_total`,
   `outbox_relay_lag`, `order_read_model_lag_seconds`) are asserted in the
   failure experiments.
8. The evidence report `docs/bootcamp/evidence/p6-resilience.md` documents
   every experiment, recovery procedure, and gap found.
9. No forbidden technology (Kubernetes, API gateway, service mesh, Redis,
   event sourcing, chaos tooling) has been introduced.
10. Git diff is clean and no unrelated files are modified.
11. The phase review process has been passed before `current-phase.md` is
    updated to Phase 07.
