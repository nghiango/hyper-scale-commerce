# Phase 18 Plan: Kotlin/JVM Engineering Maturity & Concurrency Safety

**Phase:** Phase 18 — Kotlin/JVM Engineering Maturity & Concurrency Safety

**Status:** APPROVED — IN PROGRESS; REMEDIATION REQUIRED AFTER FAILED REVIEW

**Date:** 2026-08-17

---

## 1. Phase Objective

Raise the verified Phase 17 platform to a senior Kotlin/JVM engineering baseline by making Kotlin compiler policy, null-safety, configuration, error modeling, concurrency ownership, execution-model selection, and JVM diagnostics explicit and measurable.

This phase preserves the proven Spring MVC, JDBC, Kafka, PostgreSQL, Redis, and Kubernetes architecture. It does not assume that coroutines or Java virtual threads are automatically better than the current platform-thread model. Instead, it requires a controlled comparison and an ADR-backed decision before either model may affect production execution.

## 2. Why This Phase Exists

Phase 17 proved distributed caching and read-replica scaling at 5,000 peak virtual users and 2,105.58 RPS. The repository is operationally mature, but several Kotlin/JVM concerns remain implicit:

1. Compiler and static-analysis policy is repeated per module and does not yet establish a zero-warning, strict Kotlin baseline for hand-written production code.
2. Configuration is partly type-safe and validated, but several production components still use stringly typed `@Value` injection.
3. Concurrent state exists in near caches, routing fences, rate limiting, tracing/MDC, Kafka listeners, and outbox workers, while ownership and race invariants are not documented as one system-wide policy.
4. The application uses blocking Spring MVC, JDBC, Redis, and Kafka APIs. Introducing coroutines or virtual threads without verifying transaction, context-propagation, cancellation, interrupt, and pool behavior could reduce correctness rather than improve it.
5. Load evidence reports latency and resource use, but repeatable JVM Flight Recorder, GC, allocation, thread, and live-set evidence is not yet a formal qualification gate.

Senior Kotlin engineering is demonstrated by type-safe design, disciplined concurrency, evidence-based runtime choices, and the ability to diagnose the JVM—not by adding language features without a justified production problem.

## 3. Starting Architecture / State

- Kotlin 2.2.21 on the JDK 21 toolchain with Spring Boot 4.0.0.
- Three Gradle modules: `app`, `order-query`, and `contracts`.
- Blocking Spring MVC request handling, JDBC/jOOQ persistence, Redis access, and Spring Kafka integration.
- Two horizontally scalable services on a six-node local `kind` cluster.
- Authenticated Caffeine L1 plus Redis L2 near caches and Kafka invalidation broadcasts.
- Transaction-aware primary/replica datasource routing with 100 ms lag fencing.
- Concurrent primitives including `ConcurrentHashMap`, atomic state, Caffeine single-key loading, scheduled outbox work, Kafka consumer threads, and thread-local trace context.
- Spotless, Detekt, JUnit 5, AssertJ, ArchUnit, Testcontainers, k6, Micrometer, and Prometheus rule verification.
- Phase 17 review passed with zero HTTP failures and 5,655/5,655 reconciliation under its qualification workload.
- No production coroutine dependency and no explicit virtual-thread runtime mode.

## 4. Target Architecture / State

- One shared Kotlin/JVM build policy applies consistently to all Kotlin modules.
- Hand-written production Kotlin compiles with zero warnings under the selected strict compiler options; generated jOOQ code is isolated from this policy where necessary.
- Runtime configuration uses immutable, validated `@ConfigurationProperties` rather than scattered `@Value` injection.
- Public and cross-module Kotlin boundaries have explicit nullability, immutable contracts, and exhaustive outcome modeling where multiple valid results exist.
- Every production concurrency hotspot has documented ownership, blocking behavior, bounds, lifecycle, and context-propagation requirements.
- Deterministic stress tests cover cache single-flight behavior, invalidation races, replica-fence visibility, outbox claiming, interruption, and trace/MDC cleanup.
- An accepted ADR records whether the platform retains platform threads, enables an evidence-qualified virtual-thread mode, or permits a narrowly scoped coroutine boundary.
- No production coroutine or virtual-thread adoption occurs unless it preserves Spring transaction semantics, Kafka ordering, trace/MDC context, graceful shutdown, and bounded database/broker concurrency.
- Reproducible JFR, GC, allocation, thread-dump, and live-set capture is part of the performance harness and operator runbook.
- The selected execution model passes the existing 10,000-user and 5x-spike qualification contract without correctness, latency, allocation, or resource regressions.

The service topology, bounded contexts, data ownership, Kafka contracts, PostgreSQL source-of-truth role, and Redis fail-open role remain unchanged.

## 5. Problems This Phase Addresses

| Problem | Current evidence | Phase 18 response |
|---|---|---|
| Kotlin compiler behavior is not one enforced policy | Compiler configuration is minimal at the root and quality plugins are repeated by module | Establish shared strict compiler and static-analysis gates |
| Configuration is partly stringly typed | Kafka, datasource, tracing, instance ID, and replay code contain production `@Value` injection | Replace it with immutable validated property models |
| Concurrency correctness is distributed across components | Caches, routing state, rate limits, outbox workers, Kafka listeners, and MDC use different concurrency mechanisms | Document invariants and add deterministic stress verification |
| Coroutine or virtual-thread adoption could be speculative | The runtime stack is predominantly blocking and thread-local transaction/context state is significant | Compare execution models under controlled workloads and gate any adoption through ADR-0027 |
| JVM behavior is not a phase exit gate | Current evidence captures service/container resources but not repeatable JFR, allocation, GC, or live-set analysis | Add reproducible JVM diagnostics and regression budgets |
| A senior Kotlin baseline is not explicitly documented | Existing phases focus primarily on distributed architecture and operations | Publish Kotlin/JVM design and operational guidance tied to executable checks |

## 6. Architecture Changes

No new deployable, datastore, broker, schema, or synchronous service dependency is planned.

```text
                     unchanged external topology
                                |
              +-----------------+-----------------+
              |                                   |
        app (Spring MVC)                  order-query (Spring MVC)
              |                                   |
              +-----------------+-----------------+
                                |
             shared Kotlin/JVM engineering policy
             - strict compiler/static-analysis gates
             - typed validated configuration
             - concurrency ownership and bounds
             - transaction + trace context rules
             - JFR/GC/allocation qualification
                                |
              evidence-gated execution-model decision
        platform threads | virtual threads | bounded coroutine use
```

Architecture invariants:

- Domain code remains independent of Spring, Kafka, Redis, and persistence adapters.
- `app` and `order-query` remain independently deployable and must not depend on each other.
- Blocking JDBC, Redis, and Kafka calls must never be mislabeled as non-blocking.
- Transaction-bound work must not cross threads or coroutine dispatchers unless an integration test proves the exact Spring behavior and ADR-0027 explicitly permits it.
- Database connections, Kafka in-flight sends, and downstream work remain bounded even if request concurrency increases.
- Structured concurrency must have an owner, timeout, cancellation path, and lifecycle; `GlobalScope`, unowned executors, and unbounded fan-out are forbidden.

## 7. Technology Changes

### Technologies and capabilities introduced

- Shared Gradle Kotlin compiler conventions using the existing Kotlin 2.2.21 and JDK 21 toolchain.
- Strict Kotlin nullability/annotation handling and warnings-as-errors for hand-written production Kotlin after baseline cleanup.
- JDK 21 diagnostic tooling: Java Flight Recorder, `jcmd`, thread dumps, GC logs, and native memory summaries where supported.
- Test-only `kotlinx-coroutines-core` and `kotlinx-coroutines-test`, pinned to a Kotlin-compatible version, for structured-concurrency, cancellation, and context-propagation experiments.
- Java 21 virtual threads as an experimental, configuration-gated comparison mode; they are not the default unless qualification passes and ADR-0027 accepts them.

### Existing technologies retained

- Spring MVC, Spring transactions, JDBC, jOOQ, HikariCP, Spring Kafka, Redis/Lettuce, Micrometer/Brave, JUnit 5, Testcontainers, Detekt, Spotless, ArchUnit, and k6.

### Technologies explicitly deferred

- Spring WebFlux, Reactor-based application rewrites, R2DBC, reactive Redis, and reactive Kafka.
- Production coroutine conversion without a specific non-transactional use case and ADR approval.
- Project Loom structured concurrency or scoped-value preview APIs.
- GraalVM native-image migration.
- External continuous-profiling platforms or hosted APM products.
- Cloud-managed Kubernetes, databases, Kafka, Redis, observability, GitOps, service mesh, and multi-region operation.

## 8. Non-Functional Requirements

- **Correctness:** No lost acknowledged orders, duplicate business effects, stale routing decision beyond the defined sampling window, or leaked trace/MDC context.
- **Build quality:** Zero Kotlin compiler warnings in hand-written `main` sources; all enabled Detekt and formatting checks pass.
- **Type safety:** No production `@Value` injection remains in `app` or `order-query`; configuration binding fails fast on invalid required values.
- **Concurrency:** All created work is bounded, owned, cancellable or interruptible as appropriate, and stopped during application shutdown.
- **Latency:** Existing critical API target remains p95 below 200 ms; Phase 17 tighter read targets remain catalog p95 below 10 ms and Order Query p95 below 20 ms under the comparable read-heavy profile.
- **Availability:** At least 99.9% request success during formal steady-state and spike qualification.
- **Integrity:** 100% post-run reconciliation and zero unexpected DLQ events.
- **Runtime efficiency:** No more than 10% regression from the approved Phase 18 baseline in throughput, CPU per request, or allocation per request for equivalent workloads.
- **Leak resistance:** No deadlock, continuously growing thread count, or greater than 10% steady live-set growth between equivalent post-warm-up observation windows.

## 9. Performance Expectations

| Dimension | Required outcome |
|---|---|
| Critical API latency | p95 below 200 ms in the 10,000-user profile |
| Phase 17 read latency | Catalog p95 below 10 ms; normal Order Query p95 below 20 ms in the comparable read-heavy profile |
| Throughput | No more than 10% regression from the approved Phase 18 baseline at the same offered load |
| CPU efficiency | CPU per completed request does not regress by more than 10% |
| Allocation efficiency | Allocated bytes per completed request does not regress by more than 10% for the measured representative paths |
| GC overhead | Total stop-the-world pause time remains below 1% of the steady-state observation window; no pause may violate the 200 ms API budget without a documented causal analysis |
| Connection pools | No acquisition timeout; configured Hikari bounds remain the concurrency backpressure boundary |
| Spike recovery | Return to the pre-spike latency, error, lag, and live-set band within 5 minutes after the defined 5x spike |

Any comparison between execution models must use identical dataset, topology, resource limits, JVM heap, workload, warm-up, and observation windows. A faster result is invalid if it weakens correctness, context propagation, ordering, shutdown, or generator validity.

## 10. Reliability Expectations

- Cancellation or interruption cannot leave an order transaction partially applied or an outbox event incorrectly marked published.
- Kafka record ordering and bounded redrive behavior remain unchanged.
- Trace scopes and MDC entries are always closed or cleared, including exception, cancellation, timeout, and shutdown paths.
- Cache loading remains single-flight per key within a process, and invalidation cannot permanently resurrect a known stale value.
- Replica health state remains safely visible between sampler and request threads; errors continue to fence reads to the primary.
- Graceful shutdown drains owned work within the existing 30-second lifecycle budget or records explicit unfinished work without acknowledging false success.
- Increasing request concurrency must not multiply database connections or Kafka in-flight operations beyond configured limits.
- The default execution model remains the last verified model until a candidate completes all Phase 18 gates.

## 11. Observability Requirements

- Record execution model, JVM version, heap, collector, processor count, container limits, and relevant pool sizes with every qualification.
- Capture JFR recordings for warm-up, steady state, spike, and recovery using a versioned repository configuration.
- Report allocation hot spots, lock contention/pinning, thread states, GC pauses, live-set trend, CPU hot methods, and blocking I/O samples.
- Add only low-cardinality metrics required to observe work ownership, queueing, cancellation, timeout, and execution-mode behavior.
- Preserve HTTP/Kafka correlation IDs, trace IDs, span IDs, and baggage across every accepted execution boundary.
- Provide diagnostic commands and interpretation guidance in a Kotlin/JVM operations runbook without exposing sensitive Actuator endpoints.
- Evidence reports must separate measurements from interpretations and link to raw ignored artifacts.

## 12. Security Considerations

- JFR, heap, thread, and native-memory artifacts may contain identifiers, SQL, headers, or payload fragments; store raw captures only in ignored local evidence directories and redact committed summaries.
- Do not enable `/actuator/heapdump`, remote JMX, unauthenticated diagnostic ports, or arbitrary profiling endpoints.
- Configuration-property migration must preserve Kubernetes Secret usage and must not log bound secret values.
- Coroutine names, thread names, metrics, and trace attributes must not include customer data, credentials, order payloads, or unbounded identifiers.
- Diagnostic scripts must target only explicitly selected local processes or Kubernetes pods and must fail closed when a target is ambiguous.
- Dependency additions require pinned versions, repository provenance review, and the existing build/security checks.

## 13. Data Considerations

- No schema migration, data-store change, cross-schema query, event contract change, or cache serialization change is planned.
- Type-safety improvements must preserve existing JSON field names, nullability semantics, event versions, database column mappings, and HTTP response shapes.
- Value classes or sealed outcomes are internal unless compatibility tests prove an external boundary unchanged.
- Concurrency tests involving PostgreSQL must verify row ownership and transactional outcomes through each service's owned schema.
- Formal qualification must drain asynchronous work and reconcile accepted orders, outbox records, inventory effects, and Order Query projections independently.
- JVM diagnostic evidence must not be committed when it contains production-like data values.

## 14. Explicitly Out-of-Scope Capabilities

- Cloud operations, managed services, cloud identity, cloud networking, or multi-region deployment.
- New business domains such as Customer, Cart, Payment, Shipping, or Notification.
- New microservices or additional service extraction.
- Reactive-stack migration or wholesale conversion of repository APIs to `suspend`/`Flow`.
- Replacing Kafka, PostgreSQL, Redis, Caffeine, Spring MVC, JDBC, or jOOQ.
- Preview JVM APIs in production.
- General domain-model rewrite solely to demonstrate Kotlin syntax.
- Changes to existing public API or event compatibility without a separately approved requirement and ADR.
- Always-on production profiling or third-party profiling SaaS.

## 15. Dependencies on the Previous Phase

Phase 18 depends on the completed Phase 17 baseline:

- Phase 17 review status is `PASSED`.
- `./gradlew build --no-daemon` passed all 42 tasks at review.
- The six-node local Kubernetes topology, three app pods, three Order Query pods, Redis, Patroni PostgreSQL, and Kafka HA provide the qualification runtime.
- Phase 17 cache, invalidation, routing, observability, and qualification tests provide the concurrency hotspots and regression baselines.
- ADR-0001 fixes Kotlin/JDK/Spring as the core stack.
- ADR-0009 preserves Spring Data JDBC and jOOQ boundaries.
- ADR-0013 defines tracing and thread-context requirements.
- ADR-0014 defines formal load-test validity and repetition rules.
- ADR-0016 defines lifecycle, diagnostic-security, and operational requirements.
- ADR-0026 defines cache and replica-routing correctness constraints.

`docs/bootcamp/current-phase.md` may identify Phase 18 as the active,
in-progress phase because Phase 17 already passed its review. Phase 17 remains
the latest completed and verified architecture baseline until Phase 18 passes
its own review.

## 16. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Coroutines are added to blocking code without benefit | Keep coroutine dependencies test-only unless ADR-0027 identifies a concrete safe boundary and qualification passes |
| Virtual threads increase database or Kafka pressure | Retain Hikari and producer bounds; compare queueing and downstream saturation at identical load |
| Spring transactions or security/tracing context is lost across execution boundaries | Add focused integration tests for transaction state, MDC, trace propagation, cancellation, and exceptions before runtime adoption |
| Warnings-as-errors is blocked by generated sources or third-party annotations | Apply the gate to hand-written production sources; document narrowly scoped generated-code exclusions |
| Type-safety cleanup becomes a broad domain rewrite | Limit changes to existing external/configuration boundaries and representative outcome types with compatibility tests |
| Concurrency tests become timing-dependent and flaky | Prefer barriers, latches, deterministic dispatchers, and invariant assertions; quarantine no test as a substitute for fixing it |
| Profiling distorts the workload | Measure profiler overhead and retain an unprofiled control run; invalidate comparisons above the accepted overhead budget |
| JFR or heap artifacts expose sensitive values | Keep raw artifacts ignored, redact committed evidence, and avoid remote diagnostic endpoints |
| JVM tuning is cargo-culted from one host | Record environment and compare repeated runs; accept tuning only when the causal metric improves without moving the bottleneck unsafely |
| Added quality gates slow routine feedback | Separate fast compiler/unit gates from explicit stress and qualification tasks while keeping the complete build authoritative |

## 17. ADRs That May Be Required

### ADR-0027 — Kotlin/JVM Execution, Concurrency, and Context-Propagation Strategy

**Required before production execution-model changes.** It must decide:

- the default request and background-work execution model;
- where blocking is permitted and how it is bounded;
- whether virtual threads are retained as an experiment, adopted behind a flag, or rejected;
- whether any production coroutine boundary is justified;
- transaction, security, MDC, tracing, cancellation, interrupt, timeout, and shutdown semantics;
- dispatcher/executor ownership and lifecycle;
- forbidden constructs such as `GlobalScope`, unbounded executors, and production `runBlocking`;
- measured alternatives and rollback conditions.

ADR-0001 may require a narrow amendment only if a new production concurrency library is accepted. Test-only coroutine experiments do not change the production stack decision.

No ADR is required for mechanical compiler-policy centralization or equivalent `@Value`-to-`@ConfigurationProperties` migration when external behavior is unchanged.

## 18. Ordered Implementation Tasks

### Dependency order

```text
P18-01
  +--> P18-02 --> P18-03
  +--> P18-04 --> P18-05
  +----------------> P18-06

P18-03 + P18-05 + P18-06 --> P18-07 --> P18-08
```

### P18-01 — Kotlin/JVM Baseline and ADR-0027

**Objective:** Establish the measurable Kotlin/JVM baseline and accept the rules governing blocking, threads, coroutines, virtual threads, context propagation, and lifecycle.

**Context:** Phase 17 proves platform behavior but does not define one Kotlin/JVM concurrency policy. A decision is required before implementation experiments can affect production.

**Dependencies:** Phase 17 review passed; ADR-0001, ADR-0013, ADR-0014, ADR-0016, and ADR-0026.

**Scope:** Inventory hand-written Kotlin warnings and suppressions, `@Value` usage, nullable/exception boundaries, executors, atomics, concurrent collections, thread locals, scheduled work, blocking calls, test polling, JVM flags, and resource limits. Capture a reproducible unmodified runtime baseline. Create ADR-0027.

**Implementation requirements:**

- Record exact Gradle, Kotlin, JDK, Spring Boot, JVM flag, container, and host settings.
- Classify each concurrency hotspot by owner, maximum concurrency, blocking behavior, transaction/context needs, shutdown behavior, and current tests.
- Capture at least one unprofiled and one JFR-instrumented baseline run to quantify recording overhead.
- Define evidence thresholds and rollback conditions before comparing execution models.
- ADR-0027 must evaluate current platform threads, Java 21 virtual threads, Kotlin coroutines, and a reactive rewrite; the reactive rewrite remains out of scope.

**Acceptance criteria:**

- Baseline report contains reproducible commands and raw-artifact locations.
- Every identified production concurrency hotspot has an owner and invariant.
- ADR-0027 is accepted before any production execution-model change.
- Profiling overhead is reported rather than assumed.

**Verification requirements:**

- Run the complete existing build on the unchanged baseline.
- Repeat the same smoke workload with and without JFR and calculate overhead.
- Review ADR-0027 against architecture and data-ownership rules.

**Expected files/components:** `docs/adr/0027-kotlin-jvm-execution-and-concurrency-strategy.md`, `docs/bootcamp/evidence/p18-01-kotlin-jvm-baseline.md`, ignored raw results under `build/`.

**Architecture impact:** Decision and evidence only; no runtime topology or source-of-truth change.

**Out of scope:** Enabling virtual threads, adding production coroutines, or tuning application behavior.

### P18-02 — Shared Kotlin Compiler and Static-Analysis Policy

**Objective:** Make the Kotlin/JVM build contract consistent, strict, and enforceable across `app`, `order-query`, and `contracts`.

**Context:** The JDK toolchain is centralized, but compiler/static-analysis configuration is partly module-local and hand-written production warnings are not an explicit gate.

**Dependencies:** P18-01.

**Scope:** Shared compiler options, nullability/annotation behavior, warnings-as-errors, Detekt/Spotless consistency, generated-source exclusions, and targeted forbidden-pattern rules.

**Implementation requirements:**

- Use the smallest shared Gradle convention supported by the current build; do not restructure the repository without need.
- Select only compiler options supported by the pinned Kotlin compiler and record why each is enabled.
- Resolve hand-written production warnings rather than suppressing them globally.
- Isolate generated jOOQ sources and document any unavoidable third-party warning exclusion.
- Enforce forbidden production patterns: `GlobalScope`, unowned executors, unbounded dispatcher creation, production `runBlocking`, and non-null assertions unless narrowly justified.
- Keep test-source policy practical while retaining Detekt and formatting gates.

**Acceptance criteria:**

- All modules use one documented Kotlin/JVM compiler policy.
- Hand-written `main` Kotlin sources compile with zero warnings and warnings-as-errors enabled.
- No blanket file/module suppression is added to bypass the new policy.
- Generated-source exclusions are narrow and documented.
- The complete build remains green.

**Verification requirements:**

- Run compilation with full warning output for every Kotlin source set.
- Add build-logic tests or deliberate negative fixtures proving the key policy fails when violated.
- Run `./gradlew build --no-daemon`.

**Expected files/components:** root Gradle configuration or a small convention plugin, module build files, Detekt configuration, focused build-policy tests, `docs/development/kotlin-engineering-guidelines.md`.

**Architecture impact:** Build-time governance only.

**Out of scope:** Dependency upgrades unrelated to compatibility, new modules, or wholesale formatting/refactoring.

### P18-03 — Type-Safe Configuration and Kotlin Boundary Hardening

**Objective:** Replace stringly runtime configuration and ambiguous Kotlin boundary results with immutable, validated, compatibility-tested models.

**Context:** The repository already uses `@ConfigurationProperties` in several areas, but Kafka, datasource, tracing, instance identity, and DLQ replay still contain `@Value` injection. Some null and exception outcomes rely on convention rather than explicit types.

**Dependencies:** P18-02.

**Scope:** Production `@Value` migration, validation, secret-safe rendering, explicit nullability at Java/Spring boundaries, and sealed outcome modeling for selected multi-outcome application operations where it reduces ambiguity.

**Implementation requirements:**

- Create cohesive immutable configuration models per concern; do not create one global property bag.
- Validate required URLs, durations, pool bounds, lag thresholds, topic names, and replay limits at startup.
- Preserve current defaults where they are intentional and test them explicitly.
- Keep HTTP JSON, Kafka records/headers, and database mappings byte/field compatible unless a separate decision approves change.
- Use value classes or sealed interfaces only where they enforce a real invariant; do not wrap every primitive.
- Replace broad exception conversion only where the bounded failure policy is known; preserve documented cache fail-open behavior.

**Acceptance criteria:**

- No production `@Value` injection remains in `app` or `order-query`.
- Invalid required configuration fails startup with a specific validation message.
- Secrets are never printed by validation, logs, actuator exposure, or tests.
- Existing HTTP OpenAPI shapes, Kafka contract compatibility, and database mappings remain unchanged.
- Selected multi-outcome operations are exhaustively handled by the Kotlin compiler.

**Verification requirements:**

- Property-binding unit tests cover valid values, defaults, missing values, ranges, and secret redaction.
- Spring context integration tests cover both services and production-like configuration.
- Contract serialization and API integration tests prove compatibility.
- Run the complete build.

**Expected files/components:** typed configuration classes, existing configuration/adapters, configuration tests, API/event compatibility tests, Kotlin engineering guidelines.

**Architecture impact:** Strengthens adapter and application boundaries without changing topology or ownership.

**Out of scope:** New endpoints, event versions, schemas, or general domain-model redesign.

### P18-04 — Deterministic Concurrency and Context-Safety Verification

**Objective:** Prove the correctness of existing shared state and background work under controlled races, cancellation, interruption, and shutdown.

**Context:** Phase 17 introduced concurrent near-cache and routing behavior on top of existing outbox, Kafka, rate-limiting, and tracing paths. Current tests cover functionality but not one systematic concurrency contract.

**Dependencies:** P18-01 and P18-02.

**Scope:** Cache single-flight/invalidation, routing fence visibility, rate-limit counters, outbox multi-worker claiming, Kafka listener isolation, tracing/MDC cleanup, and lifecycle interruption.

**Implementation requirements:**

- Prefer deterministic barriers, latches, controlled clocks, and test dispatchers over sleeps.
- Assert invariants and final business state, not only absence of exceptions.
- Verify cache loader execution bounds and define the mutation-versus-load race result.
- Verify atomic replica eligibility transitions and fail-closed behavior.
- Verify concurrent outbox workers never publish the same claimed row as two successful business publications.
- Verify trace/MDC cleanup on success, exception, timeout, cancellation, and reused worker threads.
- Any corrective production change must remain inside the proven component and preserve its public contract.

**Acceptance criteria:**

- Every hotspot listed by P18-01 maps to a deterministic test or a documented reason it requires qualification-level testing.
- Repeated stress suites complete without deadlock, race invariant failure, duplicate effect, stale thread-local state, or unowned live thread.
- Tests do not rely on arbitrary sleep timing for synchronization.
- Any discovered defect has a focused regression test and issue record.

**Verification requirements:**

- Run focused tests repeatedly with randomized scheduling/seed captured on failure.
- Run real PostgreSQL/Kafka/Redis integration tests for infrastructure-dependent invariants.
- Capture thread dumps during stress and after shutdown.
- Run architecture tests and the complete build.

**Expected files/components:** existing cache/routing/outbox/tracing components and tests, concurrency test utilities, `docs/bootcamp/evidence/p18-04-concurrency-safety.md`.

**Architecture impact:** Makes existing concurrency semantics explicit; no new cross-service communication.

**Out of scope:** Higher throughput tuning or changing event ordering guarantees.

### P18-05 — Structured-Concurrency and Execution-Model Qualification

**Objective:** Compare platform threads, Java 21 virtual threads, and Kotlin structured concurrency, then implement only the execution model permitted by ADR-0027 and evidence.

**Context:** The current stack is blocking and uses thread-bound Spring transactions and observability context. A senior Kotlin/JVM decision must measure the whole system rather than compare synthetic task counts alone.

**Dependencies:** P18-01, P18-02, and P18-04.

**Scope:** Test-only coroutine experiments, configuration-gated virtual-thread experiments, bounded representative blocking workloads, cancellation/timeout behavior, transaction and context propagation, downstream pool saturation, and decision finalization.

**Implementation requirements:**

- Use the same workload, topology, heap, resource limits, pool sizes, dataset, and warm-up for all candidates.
- Keep database/Kafka concurrency bounded independently of the number of request tasks.
- Exercise success, timeout, cancellation, interrupt, exception, graceful shutdown, and downstream outage paths.
- Prove whether Spring transactions, security context, MDC, Micrometer observation, and trace context remain correct.
- Coroutine experiments must use structured scopes, explicit dispatchers, parent-owned jobs, and bounded parallelism.
- No production `GlobalScope`, `runBlocking`, or dispatcher created per request is permitted.
- If neither candidate provides a safe material benefit, retain platform threads and record that as a successful evidence-based decision.

**Acceptance criteria:**

- A reproducible comparison report covers latency, throughput, CPU/request, allocation/request, thread count, pinning/lock evidence, pool pressure, context correctness, cancellation, and shutdown.
- ADR-0027 records the accepted model, rejected alternatives, rollback trigger, and configuration default.
- Any accepted runtime mode is disabled by a safe rollback switch and passes all functional/concurrency tests.
- Production coroutine dependency remains absent unless ADR-0027 identifies an exact production boundary and all relevant tests pass.
- No candidate is accepted solely because a synthetic microbenchmark is faster.

**Verification requirements:**

- Run focused transaction/context/cancellation integration tests for each candidate.
- Capture JFR and thread dumps for identical candidate workloads.
- Inject PostgreSQL, Kafka, and Redis latency/unavailability and verify bounded degradation.
- Run the complete build under the selected default mode.

**Expected files/components:** test-only execution-model harness, optional gated runtime configuration, relevant context/lifecycle tests, ADR-0027 update, `docs/bootcamp/evidence/p18-05-execution-model.md`.

**Architecture impact:** Potential execution-policy change inside existing deployables only; no topology, contract, or data-ownership change.

**Out of scope:** Reactive rewrite, preview JVM APIs, or converting all APIs to `suspend`/`Flow`.

### P18-06 — JVM Diagnostics, Profiling, and Operational Runbook

**Objective:** Make JVM performance and failure diagnosis reproducible, safe, and usable by an operator.

**Context:** Existing monitoring proves service-level outcomes, but it does not provide a standardized workflow for explaining allocation pressure, GC pauses, lock contention, pinning, thread leaks, or heap growth.

**Dependencies:** P18-01. It may proceed in parallel with P18-02 through P18-05.

**Scope:** JFR configuration and capture, GC logs, thread dumps, native-memory summaries where available, live-set sampling, report generation, redaction, and runbook procedures.

**Implementation requirements:**

- Provide fail-closed scripts that require an explicit local PID or Kubernetes pod/container target.
- Version JFR settings and record exact capture duration and environment metadata.
- Generate machine-readable summaries plus a concise human report.
- Detect or report deadlocks, thread-count trend, blocked/parked threads, allocation hot paths, GC pause distribution, live-set trend, and virtual-thread pinning when applicable.
- Compare profiled and unprofiled control runs.
- Keep raw recordings, heap artifacts, and sensitive output under ignored build directories.
- Do not expose new remote diagnostic endpoints.

**Acceptance criteria:**

- One command captures a bounded diagnostic bundle for each service.
- One command produces a redacted summary suitable for committed evidence.
- The runbook explains symptom-to-tool selection and safe collection procedures.
- The harness detects a controlled deadlock/thread leak/allocation fixture in tests without contaminating production code.
- Profiling overhead is measured and disclosed.

**Verification requirements:**

- Verify scripts with both local JVM and Kubernetes pod targets where tooling permits.
- Validate artifact ignore rules and scan committed summaries for secrets/payloads.
- Run controlled diagnostic fixtures and assert expected findings.
- Confirm restricted Actuator exposure remains unchanged.

**Expected files/components:** `performance/jvm/`, Make targets, `.gitignore`, `docs/operations/jvm-diagnostics-runbook.md`, `docs/bootcamp/evidence/p18-06-jvm-diagnostics.md`.

**Architecture impact:** Adds a test/operations diagnostic plane only.

**Out of scope:** Remote JMX, always-on profiling services, hosted APM, or automated heap-dump upload.

### P18-07 — Kotlin/JVM Load, Spike, Failure, and Soak Qualification

**Objective:** Prove that the selected Kotlin/JVM policy and execution model preserve platform correctness and improve or maintain runtime efficiency under the constitutional workload.

**Context:** Unit and stress tests cannot establish production-scale scheduler, allocation, GC, connection-pool, and recovery behavior. Formal qualification must follow ADR-0014 and compare against the Phase 18 baseline.

**Dependencies:** P18-03, P18-05, and P18-06.

**Scope:** 10,000 concurrent users, defined 5x spike, extended soak, JFR/control runs, dependency faults, JVM/container metrics, asynchronous drain, and data reconciliation.

**Implementation requirements:**

- Use the real six-node local Kubernetes topology and public ingress.
- Meet ADR-0014 generator-validity, clean-worktree, environment-capture, and three-consecutive-run rules for formal evidence.
- Execute an unprofiled control run and separate bounded JFR captures.
- Include Redis loss, replica fencing, Kafka disruption, and graceful pod termination without combining faults so broadly that attribution is lost.
- Measure p95/p99 latency, success rate, throughput, CPU/request, allocation/request, GC, live set, thread count, blocking/pinning, Hikari pressure, Kafka lag, outbox depth, and recovery time.
- Drain asynchronous work and perform independent cross-schema reconciliation.

**Acceptance criteria:**

- At least 10,000 active VUs are sustained for the defined 15-minute steady-state window in each qualifying run.
- All defined critical APIs remain below 200 ms p95 and request success is at least 99.9%.
- The comparable read-heavy profile retains catalog p95 below 10 ms and Order Query p95 below 20 ms.
- The 5x spike reaches its offered-load target and recovers within 5 minutes.
- Throughput, CPU/request, and allocation/request do not regress by more than 10% from the approved baseline.
- GC pause time, live-set trend, thread count, connection pools, and pinning meet Sections 8 and 9.
- Fault scenarios have no lost acknowledged order, duplicate business effect, context leak, deadlock, or false-success shutdown.
- Reconciliation is 100% and unexpected DLQ count is zero.

**Verification requirements:**

- Validate k6 generator headroom and reject distorted runs.
- Preserve raw k6, Kubernetes, JVM, JFR, GC, broker, database, and reconciliation artifacts under ignored build storage.
- Produce a report containing median and worst result across three consecutive qualifying runs.
- Independently rerun reconciliation and evidence parsers before task completion.

**Expected files/components:** existing `performance/` harness, JVM diagnostic tooling, qualification scripts, `docs/bootcamp/evidence/p18-07-load-spike-soak.md`.

**Architecture impact:** Verification only; confirms the accepted in-process execution policy on the unchanged distributed topology.

**Out of scope:** Cloud or multi-region qualification and claims beyond the documented single-physical-host boundary.

### P18-08 — Phase 18 Evidence Dossier and Formal Phase Review

**Objective:** Consolidate evidence, update living documentation, and decide whether Phase 18 is complete.

**Context:** Task completion labels are insufficient. The phase must pass the repository's formal review process with reproducible evidence and explicit non-claims.

**Dependencies:** P18-01 through P18-07.

**Scope:** Evidence dossier, architecture/current-phase updates, Kotlin/JVM guidelines, runbook links, ADR traceability, complete verification, diff review, and formal phase review.

**Implementation requirements:**

- Link every exit criterion to repository or runtime evidence.
- Separate raw measurements, calculations, conclusions, and uncovered failure domains.
- Record the final execution model and rollback control without overstating alternatives that were only tested.
- Update architecture and current-phase documents only after implementation and review evidence justify completion.
- Run the `phase-review` skill; do not self-declare completion from this plan.

**Acceptance criteria:**

- All tasks have completion records and reproducible verification evidence.
- ADR-0027 and any ADR-0001 amendment match the implementation.
- Architecture, runbooks, compiler policy, and qualification reports are consistent.
- Full diff contains no unrelated files, secrets, or unapproved technology.
- Formal Phase 18 review returns `PASSED`.

**Verification requirements:**

- Run `./gradlew build --no-daemon` from a clean worktree candidate.
- Run architecture, security/configuration, concurrency, diagnostic, load, failure, and reconciliation gates.
- Inspect the complete git diff and generated/untracked files.
- Execute the formal phase review independently from task implementation.

**Expected files/components:** `docs/bootcamp/evidence/p18-phase-review.md`, final evidence dossier, `docs/architecture.md`, `docs/bootcamp/current-phase.md`, Kotlin engineering guidelines, runbook index.

**Architecture impact:** Documentation and phase-governance update after verified completion only.

**Out of scope:** Starting Phase 19 or implementing cloud operations.

## 19. Task Acceptance-Criteria Matrix

| Task | Required acceptance outcome |
|---|---|
| P18-01 | Reproducible baseline and accepted ADR-0027 define execution/concurrency rules and rollback thresholds |
| P18-02 | Shared strict Kotlin policy; zero warnings in hand-written production Kotlin; complete build passes |
| P18-03 | Production `@Value` removed; validated typed configuration and external compatibility proven |
| P18-04 | Existing concurrency hotspots have deterministic invariant tests with no race, leak, deadlock, or duplicate effect |
| P18-05 | Platform/virtual/coroutine comparison completed; only an evidence-qualified model is accepted |
| P18-06 | Safe reproducible JVM diagnostic bundle, redacted report, and operator runbook verified |
| P18-07 | Three valid 10,000-VU runs plus spike/fault/soak evidence meet latency, availability, efficiency, recovery, and integrity gates |
| P18-08 | Evidence dossier complete, docs consistent, complete verification passes, and formal phase review passes |

## 20. Verification Requirements by Task

| Task | Minimum required verification |
|---|---|
| P18-01 | Full baseline build; profiled/unprofiled smoke comparison; ADR architecture review |
| P18-02 | Compiler-warning audit; negative policy fixture; Detekt; Spotless; full build |
| P18-03 | Binding/validation tests; Spring context tests; API/event compatibility tests; full build |
| P18-04 | Repeated deterministic stress; real PostgreSQL/Kafka/Redis tests; thread dumps; architecture tests |
| P18-05 | Identical execution-model workload; transaction/context/cancellation tests; JFR; dependency fault tests; full build |
| P18-06 | Local and Kubernetes capture tests; controlled diagnostic fixtures; redaction/ignore scan; Actuator security regression |
| P18-07 | Three qualifying 10,000-VU runs; 5x spike; soak; isolated faults; JVM analysis; 100% reconciliation |
| P18-08 | Complete build and all specialized gates; diff/secret review; formal `phase-review` result |

Verification discipline for every task:

- A passing test name is not evidence unless its command, environment, and relevant output are recorded.
- Timing-sensitive failures must preserve seed, timestamps, thread dump, and diagnostic artifact references.
- Performance results from a dirty worktree or saturated load generator are non-qualifying.
- Application claims must be derived from captured artifacts, not manually entered expected values.

## 21. Phase Exit Criteria

- [ ] P18-01 through P18-08 are completed and verified in dependency order.
- [x] ADR-0027 is accepted and the implementation matches its selected execution model.
- [x] Kotlin compiler/static-analysis policy is shared and hand-written production Kotlin has zero warnings.
- [x] Production configuration is immutable, validated, secret-safe, and free of `@Value` injection.
- [x] Existing concurrency hotspots have explicit ownership/invariants and deterministic verification.
- [x] No unapproved `GlobalScope`, production `runBlocking`, unbounded executor/dispatcher, or cross-thread transaction exists.
- [ ] Context propagation and cleanup pass success, failure, timeout, cancellation, interruption, and shutdown tests.
- [ ] Reproducible JVM diagnostics and the operator runbook are verified without expanding the diagnostic attack surface.
- [ ] Three consecutive valid 10,000-VU qualifications meet latency, availability, efficiency, GC, live-set, thread, pool, and generator-validity requirements.
- [ ] The defined 5x spike recovers within 5 minutes.
- [ ] Fault tests show no lost acknowledged data, duplicate business effect, deadlock, context leak, or false successful publication.
- [ ] Post-run cross-schema reconciliation is 100% and unexpected DLQ count is zero.
- [x] Architecture, Kotlin guidelines, runbooks, evidence, and current-phase documentation are consistent.
- [x] Complete build passes, full diff is reviewed, no secrets are present, and no unrelated change remains.
- [ ] Formal Phase 18 review status is `PASSED`.

Only after every exit criterion is evidenced may Phase 18 be marked completed
or replace Phase 17 as the latest verified architecture baseline. Cloud
operations remain deferred to a later approved phase.
