# Phase 09 — Chaos Engineering & Distributed Fault Tolerance

Status: **APPROVED**

This plan was produced from the actual repository state and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-08-plan.md`
- `docs/bootcamp/evidence/p8-load-engineering.md`
- `docs/bootcamp/evidence/p8-baseline.md`
- `docs/bootcamp/evidence/p8-tuning.md`
- `docs/bootcamp/evidence/p8-10k-users.md`
- `docs/bootcamp/evidence/p8-spike.md`
- ADR-0006 through ADR-0014
- The Phase 8 implementation: the external k6 harness, three-partition
  `order-placed` topic preparation, concurrent consumers, batch outbox relay,
  metrics, reconciliation scripts, and Compose topology

---

## 1. Phase objective

Prove, through safe and reproducible fault experiments under controlled active
load, that the two-deployable platform:

- fails predictably when Kafka, PostgreSQL, network paths, messages, or a
  deployable fail,
- isolates faults according to the existing asynchronous and data-ownership
  boundaries,
- preserves committed business data and idempotent effects,
- recovers after the harness removes the fault or restores the failed
  dependency/deployable,
- exposes enough evidence to explain degradation and recovery, and
- does not require a topology redesign or a future-phase technology.

This phase distinguishes **application recovery after restoration** from
**infrastructure self-healing**. Docker Compose has no orchestrator or restart
controller. A process-crash experiment therefore measures behavior after the
chaos harness explicitly restarts the killed deployable; it must not be
reported as automatic infrastructure restart.

## 2. Why this phase exists

The constitution defines the final evolutionary stage as Chaos Engineering:

1. Engineering Foundation
2. Modular Monolith
3. Performance Engineering
4. Event-Driven Architecture
5. CQRS
6. Service Extraction
7. Resilience Engineering
8. Observability
9. Load Engineering
10. **Chaos Engineering** <- this phase

Phase 6 verified deterministic dependency outages at low load. Phase 8 proved
the current topology under nominal 10,000-VU and 5x-spike workloads, tuned the
outbox/event pipeline, and added deterministic data reconciliation. Neither
phase combines controlled faults with sustained traffic or measures isolation,
degradation, and recovery as one experiment.

Phase 9 closes that gap. It also closes an existing constitutional compliance
gap before chaos qualification: both Kafka consumers currently use a fixed
retry delay, while `docs/constitution.md` requires bounded exponential backoff
with jitter and an explicit retryable/non-retryable classification.

## 3. Starting architecture / state

| Item | Actual Phase 8 state |
|---|---|
| Deployables | `app` on port 8080 and `order-query` on port 8081; `contracts` is a shared library |
| Persistence | One PostgreSQL 16 instance; `app` owns `catalog`, `order`, and `inventory`; `order-query` owns `order_query` |
| Messaging | One Kafka broker, replication factor 1; load preparation creates three partitions for `order-placed` |
| Broker limitation | A single broker can demonstrate reachability loss, latency, connection cuts, restart, replay, and consumer-group rebalances; it cannot demonstrate broker failover or partition-leader election to another broker |
| DLQ topology | Both consumer groups publish failed `order-placed` records to the shared `order-placed-dlq`; there is no independently configured order-query DLQ |
| Outbox relay | 100ms scheduled cycle, claim limit 500, batch `markPublished(Collection<Long>)` |
| Consumers | Inventory and Order query consumers are idempotent, use listener concurrency 3, manual acknowledgment, and a fixed 1-second delay with 3 retry attempts |
| Process recovery | Compose health checks exist, but no restart policy or orchestrator automatically restarts a killed service |
| Observability | Actuator Prometheus-format metrics, JSON logs, trace/correlation context, HTTP/SLO metrics, Hikari metrics, event/DLQ/lag metrics |
| Load harness | Pinned k6 runner, workload definitions, environment preflight, reset, metric snapshots, active drain, and cross-schema reconciliation |
| Qualified nominal capacity | Three 10,000-VU runs and three 5x-spike runs passed on the recorded single-host qualification environment |
| Network paths | Services currently connect directly to `postgres:5432` and `kafka:9092`; no traffic flows through Toxiproxy |

## 4. Target architecture / state

The product topology remains two deployables, one PostgreSQL instance, and one
Kafka broker. Phase 9 adds a test-only chaos plane and explicit proxy routing:

```text
                         k6 controlled workload
                                  |
                       app :8080   |   order-query :8081
                          |        |        |
             +------------+--------+--------+------------+
             |       chaos control plane (test only)     |
             | scenario runner, watchdog, exact-target   |
             | validation, Toxiproxy API, Docker restore |
             +---------+----------------------+-----------+
                       |                      |
          app-postgres / query-postgres     shared Kafka proxy
                 Toxiproxy paths          or scoped network cut
                       |                      |
                       v                      v
                  PostgreSQL              Kafka broker
```

Target properties:

- A chaos-only Compose overlay routes the selected dependency path through a
  pinned Toxiproxy image. A preflight connection test proves that disabling a
  proxy interrupts the intended client before an experiment is accepted.
- PostgreSQL uses separate app and order-query proxies so one client path can
  be degraded independently.
- Kafka fault injection accounts for Kafka broker metadata. The chaos overlay
  must advertise a broker endpoint that traverses the proxy, or use an exact,
  scoped container-network cut. A bootstrap proxy that Kafka clients bypass
  after metadata discovery is invalid.
- Faults are injected one at a time unless a later task explicitly defines a
  composite scenario, so the cause of degradation remains attributable.
- Removing a network toxic or restoring a dependency is automated. Restarting
  a killed application container is an explicit harness action and is labeled
  as such in evidence.
- Each experiment has a steady-state hypothesis, fault window, expected
  degradation, recovery condition, integrity condition, abort condition, and
  evidence bundle.
- Kafka retry behavior becomes constitution-compliant before poison-message
  and broker chaos qualification.

## 5. Problems this phase addresses

- Dependency and process failures have not been tested under controlled active
  load.
- Direct Compose network paths currently bypass any prospective proxy.
- The single-broker topology has been described as capable of leader-election
  tests even though no alternate broker exists.
- Current fixed consumer retry delays violate the constitutional requirement
  for exponential backoff with jitter.
- Retryable and non-retryable consumer failures are not documented as an
  explicit policy.
- Both consumers share one DLQ, so poison-message evidence must attribute
  attempts and outcomes by service/consumer rather than by a fictional second
  topic.
- Compose does not restart killed processes automatically; recovery claims
  need precise ownership and language.
- Existing load-reset tooling accepts overridable container names and truncates
  tables, which is insufficiently constrained for a Docker-capable chaos
  harness.
- Fault-specific availability and latency expectations are not yet defined;
  a universal success or latency threshold would be false during PostgreSQL or
  process outages.

## 6. Architecture changes

- Add a test-only chaos harness under `performance/chaos/`.
- Add a chaos-only Compose overlay with a pinned Toxiproxy service, dedicated
  ephemeral PostgreSQL volume, and explicit dependency routing.
- Keep normal `make up`, `make services`, and Phase 8 load commands unchanged.
- Replace fixed Kafka retry delay with bounded exponential backoff plus jitter
  and explicit exception classification in both consumer configurations.
- Preserve the shared `order-placed-dlq`; add service/consumer attribution to
  evidence or bounded metric tags if existing per-service endpoints/logs are
  insufficient.
- Do not add a multi-broker Kafka cluster, application deployable, synchronous
  inter-service call, circuit breaker, bulkhead library, or load-shedding
  mechanism without evidence and an approved ADR.
- Do not add a runtime restart policy merely to make a chaos test pass. Process
  restoration belongs to the harness in this phase.

## 7. Technology changes

### Introduced

- **Toxiproxy, test-only and digest-pinned** for deterministic TCP latency,
  jitter, connection timeout/reset, bandwidth restriction, and packet-loss
  experiments on explicitly routed dependency paths.
- **POSIX shell and Docker Engine CLI, test-only** for exact-target container
  inspection, stop/kill/start, health polling, and cleanup.

ADR-0015 must select and record the exact Toxiproxy version and digest before
P9-03 implements the harness.

### Retained

- Kotlin, Spring Boot, Gradle, PostgreSQL, Flyway, Kafka, Spring Kafka,
  Testcontainers, Spring Data JDBC, jOOQ, Micrometer, Brave, MDC, Docker,
  Docker Compose, and the pinned k6 load runner.

### Explicitly deferred

- Multi-broker Kafka, broker failover, and partition-leader election between
  brokers.
- Kubernetes, service mesh, API gateway, service discovery, autoscaling, and
  infrastructure self-healing claims.
- Redis, Elasticsearch, additional databases, separate physical databases,
  read replicas, and additional application deployables.
- Central Prometheus/Grafana, APM, log aggregation, and distributed load
  generators.
- Kernel/host network manipulation, `tc`/netem, eBPF fault injection, disk or
  filesystem corruption, clock skew, and host resource exhaustion.
- Circuit breakers, bulkheads, rate limiting, and load shedding unless P9-08
  produces evidence that an existing mechanism cannot contain a documented
  cascade; any introduction requires ADR-0016 and plan approval.

## 8. Non-functional requirements

- `make verify`, `make load-smoke`, and `make chaos-smoke` pass from a clean
  checkout after their required infrastructure is available.
- Long chaos scenarios remain opt-in and never run implicitly in `make verify`.
- Every scenario records commit SHA, dirty state, image digests, Compose
  project, exact container IDs, network and volume IDs, host/Docker resources,
  workload, random seed, UTC timestamps, and fault parameters.
- Dirty-worktree runs are non-qualifying.
- Each qualifying scenario runs at least three times without changing code,
  configuration, resources, data cardinality, or fault parameters.
- A control run with identical load and no fault precedes each experiment
  family.
- The harness must prove the intended path is proxied or disconnected before
  starting the measurement window.
- A watchdog bounds setup, fault, recovery, and total experiment duration.
- Cleanup runs from shell traps on success, failure, timeout, and interruption.
- A failed cleanup makes the run invalid and blocks subsequent scenarios.
- No expected failure status is silently removed from reports. Client errors,
  timeouts, readiness changes, and dropped iterations remain visible and are
  classified by the scenario hypothesis.
- No tuning or reliability mechanism is introduced during an accepted chaos
  run.

## 9. Performance expectations

Performance expectations are fault-specific:

- **Control windows:** retain the Phase 8 critical-API p95 below 200ms and at
  least 99.9% request success.
- **Kafka path degradation:** Catalog reads remain below 200ms p95 with at
  least 99.9% success because they do not depend on Kafka. Successful
  `POST /orders` requests remain committed to PostgreSQL/outbox; projection
  visibility may exceed its nominal budget and must be reported as stale.
- **Order-query-only PostgreSQL path degradation:** `app` Catalog and Order
  command SLOs remain at Phase 8 targets. Order queries fail or time out within
  the documented datasource/HTTP budget rather than hang indefinitely.
- **Shared PostgreSQL outage:** database-backed APIs are expected to fail.
  Success is not required during the fault; bounded response time, absence of
  partial commits, bounded resource use, and recovery are required.
- **Application process crash:** requests to the killed deployable are expected
  to fail until the harness restores it. The surviving deployable's independent
  APIs are measured against their Phase 8 targets where their own dependencies
  remain healthy.
- **Post-recovery:** critical APIs return below 200ms p95 and at least 99.9%
  success during the final stable window. The recovery deadline is defined per
  scenario in ADR-0015 from existing health-check, timeout, Phase 6 recovery,
  and Phase 8 drain evidence; arbitrary sub-10ms or 15-second thresholds are
  not used.

## 10. Reliability expectations

- **Kafka unavailable:** successful Orders remain durable in PostgreSQL and the
  outbox; publication and both projections catch up after the path returns.
- **PostgreSQL unavailable:** no partial Order/outbox transaction is committed;
  accepted work from before the fault remains intact; pools recover after
  restoration.
- **Consumer retry:** retryable failures use bounded exponential backoff with
  jitter; non-retryable payload/schema failures reach the shared DLQ without
  unnecessary retry; the policy and exception mapping are explicit.
- **Poison records:** healthy records on other partitions continue. Healthy
  records behind a poison record on the same partition may wait only for the
  documented bounded retry/DLQ window, then resume.
- **Duplicate delivery:** consumer restarts and rebalances produce no duplicate
  Inventory or read-model business effects.
- **`app` crash:** `order-query` continues serving already projected data.
  After harness restart, `app` resumes relay/consumption without corrupting
  committed state.
- **`order-query` crash:** Catalog and Order commands continue; Kafka retains
  events; after harness restart, projection catches up idempotently.
- **Recovery ownership:** applications reconnect and drain automatically after
  a network toxic is removed or dependency is restored. The harness explicitly
  starts killed containers; evidence must not call that infrastructure
  self-healing.
- **Data integrity:** every successful `POST /orders` has one Order, one outbox
  record, one logical Inventory reservation, and one read-model row after drain.

## 11. Observability requirements

- Every scenario captures k6 endpoint latency/status/error metrics, service
  health/readiness, HTTP/JVM/Hikari metrics, outbox count/oldest age, Kafka
  consumer lag, event counters, shared-DLQ offsets, container resources, and
  Toxiproxy state/metrics.
- Fault start, fault removal/restoration, service readiness, first successful
  reconnect, backlog drain, and reconciliation completion use UTC timestamps.
- Metrics come from the existing per-service Actuator endpoints; a Prometheus
  server is not introduced.
- Shared-DLQ evidence attributes failed records to the originating consumer by
  per-service counters/logs and Kafka failure headers where available.
- Logs retain service, trace, span, correlation, exception class, retry
  attempt, and fault-scenario identifier without adding order IDs or trace IDs
  as metric labels.
- Evidence distinguishes observed facts from inference and includes failed or
  invalid runs.

## 12. Security and blast-radius considerations

- Chaos runs use a dedicated Compose project and dedicated ephemeral database
  volume. The harness refuses the normal/shared project or an unknown volume.
- Before any mutation, resolve exact container and network IDs by required
  Compose labels; never act on a name substring, wildcard, unresolved variable,
  or user-supplied arbitrary container name.
- Permit only the approved chaos project, services, proxy names, and bridge
  network. Host networking and external endpoints are forbidden.
- Require an explicit non-secret confirmation token naming the chaos project
  before `kill`, `stop`, `start`, network disconnect, topic reset, or database
  truncation.
- Validate that PostgreSQL and Kafka contain only synthetic chaos-test data
  before reset. Abort if environment identity cannot be proven.
- Capture original container/network/proxy state and restore it through an
  idempotent cleanup trap. After each scenario, verify all toxics are removed,
  proxies are enabled, and expected containers are healthy. Final teardown
  separately verifies that no chaos-project containers, networks, or volumes
  remain.
- Fault injection never runs against production, shared development, remote
  URLs, host processes, host network interfaces, or Docker resources outside
  the exact chaos project.
- Scripts contain no credentials, customer data, or secrets in commands,
  summaries, or committed evidence.

## 13. Data considerations

- Each scenario starts with deterministic synthetic seed data in the dedicated
  chaos database/volume and freshly prepared three-partition topics.
- Reset operations are destructive only inside the validated chaos project.
- Capture successful Order IDs from the load generator; do not prove integrity
  solely through aggregate counts.
- Reconciliation reads each owned schema independently and compares business
  keys across results; application code gains no cross-schema dependency.
- Shared-DLQ messages are expected in poison tests and are reconciled against
  the injected poison set rather than treated as unexplained residue.
- Non-poison scenarios require zero new DLQ records from valid messages.
- Reconciliation occurs only after outbox and consumer lag reach their
  scenario-specific drain condition or the recovery deadline expires.
- Event payloads and contracts remain backward-compatible.

## 14. Explicitly out-of-scope capabilities

- Multi-broker Kafka availability, replica failover, or broker leader election.
- Multi-region, multi-zone, physical-host, disk-corruption, clock-skew, or
  kernel-level chaos.
- Simultaneous arbitrary faults or randomized production chaos.
- Infrastructure restart automation or claims of orchestrator self-healing.
- New business contexts, new deployables, or synchronous cross-service calls.
- Kubernetes, service mesh, API gateway, Redis, Elasticsearch, separate
  physical databases, central observability backends, or distributed k6.
- Circuit breakers or bulkhead libraries without a measured cascading-failure
  gap, ADR-0016, and explicit approval.
- Improving nominal Phase 8 capacity or changing SLOs.

## 15. Dependencies on the previous phase

Phase 9 depends on verified Phase 8 artifacts:

- ADR-0014 and the workload contract.
- Pinned k6 runner and environment validity checks.
- Load smoke, 10,000-VU, and 5x scenarios.
- Tuned outbox relay and three-partition topic preparation.
- Metric snapshot, reset, active-drain, and reconciliation scripts.
- Phase 8 qualification evidence and measured environment.
- `make verify` and `make load-smoke` passing before chaos changes begin.

The Phase 8 scripts are starting points, not automatically safe for chaos.
P9-03 must wrap or replace destructive operations with the stricter Section 12
guards before they are used by chaos targets.

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Proxy exists but clients bypass it | False passing experiment | Override real endpoints and advertised Kafka listener; preflight disable-path proof |
| Docker command targets the wrong resource | Data loss or unrelated outage | Exact project labels/IDs, confirmation token, dedicated ephemeral stack, no wildcard targeting |
| Cleanup fails | Subsequent results invalid or environment remains broken | Idempotent traps, watchdog, post-cleanup health/state verification, block next run |
| SIGKILL is misreported as self-healing | False reliability claim | Harness-owned explicit restart and precise evidence terminology |
| Single broker is mistaken for HA Kafka | Unsupported failover claim | Limit experiments to reachability/restart/replay; defer multi-broker |
| Retry storm amplifies dependency recovery | Slow recovery/cascading load | Constitutional exponential backoff with jitter, bounded attempts, measured retry rate |
| Poison retry blocks same partition | Healthy-message delay | Measure bounded same-partition delay; verify other partitions continue and same partition resumes after DLQ |
| Shared DLQ obscures consumer attribution | Incorrect evidence | Per-service counters/logs plus failure headers and injected-message manifest |
| Fault and overload are conflated | Unattributable failure | One fault per scenario, identical no-fault control, sustainable workload rather than simultaneous maximum spike by default |
| Persistent connection masks a toxic | Fault is weaker than intended | Force/verify connection turnover where appropriate and assert proxy metrics/state |
| Reconciliation starts before drain | False data-loss report | Poll outbox and consumer lag to bounded recovery deadline before reconciliation |
| Remediation expands architecture | Scope violation | Stop, create ADR-0016, and request approval before new reliability mechanisms |

## 17. ADRs that may be required

- **ADR-0015 — Chaos Engineering, Retry, and Fault-Injection Strategy
  (required).** It records the pinned Toxiproxy image, routing model, exact
  failure hypotheses, workload selection, blast-radius controls, recovery
  ownership, evidence validity, constitutional retry policy, and single-broker
  limitations.
- **ADR-0016 — Cascading-Failure Remediation (conditional).** Required only if
  P9-08 proves that existing timeouts, pool boundaries, and asynchronous
  isolation are insufficient and proposes a circuit breaker, bulkhead,
  backpressure, rate limit, or load-shedding mechanism. Implementation must
  wait for ADR approval and a revised task scope.

## 18. Ordered implementation tasks

### P9-01 — ADR: chaos, retry, and fault-injection strategy

- **Objective:** Approve the experiment and retry strategy before changing
  runtime behavior or adding fault tooling.
- **Context:** Chaos tooling has destructive capability, Kafka is single-broker,
  Compose lacks restart automation, and the current fixed retry policy conflicts
  with the constitution.
- **Dependencies:** Phase 8 complete and reviewed.
- **Scope:** Documentation only.
- **Implementation requirements:**
  - Create ADR-0015 covering Section 17's required decisions.
  - Define each scenario's steady state, fault, expected degradation, recovery
    condition, integrity invariant, abort condition, and evidence.
  - Derive recovery budgets from existing timeout, health-check, Phase 6, and
    Phase 8 evidence rather than inventing thresholds.
  - Explicitly reject broker failover/leader-election and infrastructure
    self-healing claims for the current topology.
- **Acceptance criteria:**
  - ADR-0015 is accepted and contains the exact pinned Toxiproxy digest.
  - All failure expectations are documented; undocumented expectations are
    labeled requirement gaps rather than assumed.
  - Retryable/non-retryable exception classes and exponential-backoff/jitter
    bounds are explicit.
- **Verification requirements:** Architecture, reliability, security, and
  document review against AGENTS.md, constitution Sections 5/7/8, ADR-0012,
  ADR-0014, and actual Compose/Kafka configuration.
- **Expected files/components:**
  - `docs/adr/0015-chaos-engineering-strategy.md`
- **Architecture impact:** Records test-plane and retry decisions; no code or
  infrastructure change.
- **Out of scope:** Harness or retry implementation.

### P9-02 — Constitutional Kafka retry-policy compliance

- **Objective:** Make both Kafka consumers use the approved bounded
  exponential-backoff-with-jitter policy and explicit failure classification.
- **Context:** Both consumers currently use `FixedBackOff(1000, 3)`, contrary
  to the constitution and unsuitable for recovery under concurrent faults.
- **Dependencies:** P9-01.
- **Scope:** Kafka consumer configuration and focused tests in `app` and
  `order-query`.
- **Implementation requirements:**
  - Implement the ADR-0015 retry schedule with maximum attempts and elapsed
    time bounded.
  - Classify transient dependency failures as retryable and malformed/schema or
    explicitly permanent failures as non-retryable.
  - Preserve manual acknowledgment, DLQ publication, idempotency, metrics,
    tracing, and shared-DLQ behavior.
  - Record retry attempt/delay/service context without high-cardinality tags.
- **Acceptance criteria:**
  - Tests prove retry delays grow exponentially, include jitter within defined
    bounds, and stop at the approved limit.
  - A non-retryable poison payload reaches the shared DLQ without unnecessary
    retries; a retryable synthetic failure recovers before exhaustion.
  - Valid messages still process and duplicate delivery creates no duplicate
    business effect.
  - `make verify` passes.
- **Verification requirements:** Focused unit tests using deterministic
  clock/random inputs where practical; Inventory and Order query failure
  integration tests; `make verify`; configuration review against ADR-0015.
- **Expected files/components:**
  - `app/src/main/kotlin/.../config/KafkaConfig.kt`
  - `order-query/src/main/kotlin/.../config/KafkaConfig.kt`
  - focused unit/integration tests in both modules
- **Architecture impact:** Changes an existing reliability policy without
  changing delivery semantics or topology.
- **Out of scope:** New broker, new DLQ topic, circuit breaker, or chaos harness.

### P9-03 — Safe chaos harness and verified proxy routing

- **Objective:** Build a blast-radius-controlled harness that proves each
  intended dependency path is faultable and always restores the environment.
- **Context:** Merely starting Toxiproxy is ineffective while services connect
  directly to PostgreSQL/Kafka, and Docker CLI access can affect unrelated
  resources without strict target validation.
- **Dependencies:** P9-01, P9-02.
- **Scope:** Test-only Compose overlay, chaos scripts, Make targets, safety
  checks, and smoke experiment.
- **Implementation requirements:**
  - Add dedicated app-PostgreSQL, order-query-PostgreSQL, and Kafka proxy paths.
  - Configure Kafka advertised metadata so client traffic continues through
    the chaos path; fail preflight if clients bypass it.
  - Implement all Section 12 controls, state capture, watchdog, idempotent
    cleanup traps, and post-cleanup verification.
  - Add scenario manifest/schema containing workload, fault, timing,
    expectations, abort limits, and output directory.
  - Add `make chaos-smoke` and bounded scenario-specific targets without
    changing normal Compose or load targets.
- **Acceptance criteria:**
  - Preflight disabling each proxy interrupts only the intended path and proxy
    metrics show application traffic.
  - `make chaos-smoke` injects one short latency toxic, removes it, restores
    health, writes evidence, and exits successfully.
  - Unknown project/container/network/volume/proxy/remote target and missing
    confirmation token are rejected before mutation.
  - Forced interruption still triggers cleanup and leaves no toxic or chaos
    resource active.
  - `make verify` and `make load-smoke` remain unchanged and pass.
- **Verification requirements:** Compose config inspection; positive and
  negative safety tests; intentional interruption test; path-bypass test;
  `make chaos-smoke`; `make verify`; `make load-smoke`.
- **Expected files/components:**
  - `performance/compose.chaos.yml`
  - `performance/chaos/`
  - `Makefile`
  - chaos harness documentation
- **Architecture impact:** Adds a test-only fault plane; product topology is
  unchanged.
- **Out of scope:** Long fault qualification and application remediation.

### P9-04 — Kafka reachability and degraded-network experiments

- **Objective:** Verify outbox buffering, stale-read behavior, replay, and
  recovery when the single Kafka broker path is slow, lossy, cut, or restarted.
- **Context:** One broker cannot prove failover or leader election, but the
  existing outbox and durable log should preserve accepted work across broker
  unavailability.
- **Dependencies:** P9-03.
- **Scope:** Kafka proxy/network and single-broker restart experiments under the
  approved sustainable mixed workload.
- **Implementation requirements:**
  - Run separate latency+jitter, packet-loss/reset, connection-cut, and broker
    restart scenarios; do not combine them in one accepted run.
  - Execute an identical no-fault control for comparison.
  - Verify Catalog independence, Order/outbox durability, stale query behavior,
    retry rate, outbox growth, consumer lag, restoration, drain, and integrity.
  - Harness explicitly restores/restarts the broker where required.
- **Acceptance criteria:**
  - Catalog retains its Phase 8 SLO during Kafka-only faults.
  - Every successful Order remains committed with one outbox record.
  - No valid message enters the DLQ and no duplicate business effect occurs.
  - After restoration, outbox and consumer lag drain within ADR-0015's budget,
    post-recovery SLOs pass, and reconciliation is exact.
  - Evidence makes no broker failover or leader-election claim.
- **Verification requirements:** Three qualifying repetitions per fault type;
  control comparison; proxy/broker state evidence; metric/log timeline; ID-based
  reconciliation; failure-analysis report.
- **Expected files/components:**
  - `performance/chaos/scenarios/kafka-*.yml`
  - `docs/bootcamp/evidence/p9-kafka-network.md`
- **Architecture impact:** None; test execution only.
- **Out of scope:** Multi-broker Kafka and simultaneous PostgreSQL fault.

### P9-05 — PostgreSQL path degradation and outage experiments

- **Objective:** Verify bounded database failure, transaction atomicity, pool
  isolation, reconnect, and data preservation.
- **Context:** PostgreSQL is the source of truth and is physically shared, but
  separate client proxies allow one deployable path or both paths to be
  degraded deliberately.
- **Dependencies:** P9-03.
- **Scope:** App-only, order-query-only, and shared PostgreSQL path experiments.
- **Implementation requirements:**
  - Run separate latency, connection starvation/timeout, connection cut, and
    database restart scenarios with a no-fault control.
  - Classify expected HTTP failures per Section 9 rather than applying a false
    universal availability target.
  - Capture transaction results, Hikari pending/acquire metrics, HTTP latency,
    JVM threads/memory, readiness, locks, reconnect, outbox state, and recovery.
  - Harness explicitly restores/restarts PostgreSQL where required.
- **Acceptance criteria:**
  - Commands either commit Order and outbox atomically or fail with neither;
    no partial transaction exists.
  - Requests fail within ADR-0015's bounded budgets; pools, threads, and memory
    remain bounded.
  - An order-query-only path fault does not breach `app` Phase 8 SLOs.
  - After restoration, services reconnect, readiness returns, post-recovery
    SLOs pass, durable work drains, and reconciliation is exact.
- **Verification requirements:** Three repetitions per fault class; database
  transaction/integrity queries; control comparison; health/resource timeline;
  ID-based reconciliation; failure-analysis report.
- **Expected files/components:**
  - `performance/chaos/scenarios/postgres-*.yml`
  - `docs/bootcamp/evidence/p9-postgres.md`
- **Architecture impact:** None; test execution only.
- **Out of scope:** Database corruption, failover, replica, or schema change.

### P9-06 — Concurrent poison-message and shared-DLQ isolation

- **Objective:** Prove bounded poison handling and healthy-record progress on
  all three partitions for both consumer groups under load.
- **Context:** Inventory and Order query consume the same topic independently
  and publish failures to the same DLQ, so evidence must attribute outcomes by
  consumer rather than assume two DLQ topics.
- **Dependencies:** P9-02, P9-03.
- **Scope:** Kafka message injection, retry/DLQ verification, and consumer
  continuity.
- **Implementation requirements:**
  - Create a manifest of malformed/non-retryable and transient/retryable test
    records with deterministic keys covering all partitions.
  - Inject poison and valid sentinel records before and after each poison on
    every partition under the approved workload.
  - Attribute attempts, DLQ publications, and recovery to Inventory and Order
    query using per-service endpoints/logs and failure headers.
  - Measure other-partition continuity and bounded same-partition delay.
- **Acceptance criteria:**
  - Non-retryable records reach the shared DLQ according to ADR-0015 without
    unnecessary retry; retryable records follow the exponential/jitter policy.
  - The observed shared-DLQ count and attribution match the injection manifest
    and both consumer groups exactly.
  - Other partitions continue during retry; each affected partition resumes
    valid processing within its bounded retry/DLQ window.
  - No valid record is sent to DLQ and no duplicate business effect occurs.
- **Verification requirements:** Three repetitions; partition/offset manifest
  comparison; retry timing assertions; per-service metric/log review; final
  reconciliation; `make verify`.
- **Expected files/components:**
  - `performance/chaos/scenarios/poison-messages.yml`
  - poison injection/attribution scripts
  - `docs/bootcamp/evidence/p9-poison-dlq.md`
- **Architecture impact:** Verifies the existing shared-DLQ topology.
- **Out of scope:** Creating a second DLQ or changing event contracts.

### P9-07 — Application process crash and harness-controlled restoration

- **Objective:** Verify isolation and application recovery after abrupt `app`
  or `order-query` termination and explicit harness restart.
- **Context:** Compose does not automatically restart killed containers; the
  experiment must not mislabel harness restoration as self-healing.
- **Dependencies:** P9-03.
- **Scope:** One deployable at a time, under the approved sustainable workload.
- **Implementation requirements:**
  - Resolve and record the exact chaos-project container ID before SIGKILL.
  - Kill only the selected deployable, observe the surviving service, wait the
    ADR-defined fault window, then explicitly start the killed container.
  - Capture request failures, Kafka group rebalance/lag, readiness, reconnect,
    redelivery, drain, and data integrity.
  - Repeat independently for `app` and `order-query`.
- **Acceptance criteria:**
  - Only the selected container stops; the harness records that it performed
    restoration.
  - During `order-query` loss, Catalog and successful Order commands retain
    Phase 8 SLOs and events remain durable.
  - During `app` loss, order-query continues serving already projected data.
  - After restart, readiness and post-recovery SLOs return within ADR-0015's
    budget; backlog drains; redelivery creates no duplicate effect; integrity
    reconciliation is exact.
- **Verification requirements:** Three app-kill and three order-query-kill
  repetitions; exact ID/label audit; request/health/rebalance timeline;
  reconciliation; cleanup verification; failure-analysis report.
- **Expected files/components:**
  - `performance/chaos/scenarios/app-crash.yml`
  - `performance/chaos/scenarios/order-query-crash.yml`
  - `docs/bootcamp/evidence/p9-process-crash.md`
- **Architecture impact:** None; harness restores processes explicitly.
- **Out of scope:** Orchestrator restart policy or infrastructure self-healing.

### P9-08 — Cascading-failure and resource-isolation qualification

- **Objective:** Verify that existing asynchronous boundaries, timeouts, and
  resource pools prevent a scoped degradation from exhausting unrelated paths.
- **Context:** `order-query` is not synchronously downstream of `app`; a useful
  cascade experiment must name a shared or proxied resource path instead of
  assuming a direct dependency.
- **Dependencies:** P9-04, P9-05, P9-06, P9-07.
- **Scope:** Evidence-only composite analysis using one controlled degradation
  at a time; conditional remediation is excluded.
- **Implementation requirements:**
  - Verify Catalog isolation during Kafka degradation.
  - Verify `app` isolation during order-query-only PostgreSQL path degradation.
  - Verify bounded thread/pool behavior during shared PostgreSQL degradation.
  - Compare each case with its control using Phase 8 SLOs and ADR-0015 budgets.
  - If containment fails, document the causal chain and stop before adding a
    new reliability mechanism.
- **Acceptance criteria:**
  - Independent paths retain their applicable Phase 8 p95 below 200ms and
    99.9% success targets; no invented sub-10ms target is used.
  - Hikari pending connections, HTTP threads, JVM memory, retry rate, outbox,
    and consumer lag remain within documented bounded behavior or fail the
    experiment.
  - No cascading process crash, readiness loss outside the expected fault
    scope, or data corruption occurs.
  - Any failed containment produces an ADR-0016 proposal and revised plan,
    not an unapproved implementation.
- **Verification requirements:** Three repetitions of each isolation case;
  control comparison; resource and health timelines; failure analysis;
  architecture review.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p9-cascading-failure.md`
  - ADR-0016 proposal only if evidence requires it
- **Architecture impact:** None unless a separately approved remediation phase
  follows.
- **Out of scope:** Implementing circuit breakers, bulkheads, rate limits,
  load shedding, or topology changes.

### P9-09 — Chaos evidence and phase gate

- **Objective:** Consolidate all control, fault, recovery, integrity, safety,
  and invalid-run evidence and determine whether Phase 9 passes.
- **Context:** Chaos claims require reproducible timelines and negative evidence,
  not only successful final state.
- **Dependencies:** P9-01 through P9-08.
- **Scope:** Documentation, final verification, architecture review, and phase
  review inputs.
- **Implementation requirements:**
  - Create an evidence index covering every task and scenario.
  - Record exact commands, environment, hypotheses, control results, fault
    parameters, observed degradation, recovery milestones, reconciliation, and
    raw artifact locations.
  - Include failed, aborted, invalid, and cleanup-failed runs.
  - State single-broker, harness-restoration, and environment limitations.
  - Inspect complete git status/diff and ensure no raw results, secrets, chaos
    residue, or unrelated changes are committed.
- **Acceptance criteria:**
  - `docs/bootcamp/evidence/p9-chaos-engineering.md` maps every Phase 9 claim to
    reproducible evidence.
  - All required scenarios pass three qualifying repetitions and cleanup.
  - `make verify`, `make load-smoke`, and `make chaos-smoke` pass from a clean
    checkout.
  - Architecture review and phase review pass.
  - Any unresolved retry, safety, integrity, recovery, or containment gap keeps
    Phase 9 incomplete.
- **Verification requirements:** Re-run smoke; validate all result links and
  manifests; compare summaries to raw artifacts; full git diff and secret
  review; architecture review; phase review.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p9-chaos-engineering.md`
- **Architecture impact:** None; evidence and gate only.
- **Out of scope:** Remediation, new phase planning, or phase advancement.

### Dependency graph

```text
P9-01 --> P9-02 --> P9-03 --+--> P9-04 --+
                            +--> P9-05 --+
                            +--> P9-06 --+--> P9-08 --> P9-09
                            +--> P9-07 --+
```

### Required execution order

1. P9-01
2. P9-02
3. P9-03
4. P9-04, P9-05, P9-06, and P9-07 after P9-03, one scenario at a time
5. P9-08
6. P9-09

## 19. Phase exit criteria

Phase 9 is complete only when all of the following are true:

1. P9-01 through P9-09 are implemented and independently verified.
2. ADR-0015 is accepted before retry or chaos implementation.
3. Both Kafka consumers use verified bounded exponential backoff with jitter
   and explicit retryable/non-retryable classification.
4. The chaos harness proves traffic traverses each intended proxy/fault path
   and enforces every Section 12 blast-radius control.
5. Cleanup succeeds after normal completion, assertion failure, timeout, and
   interruption; no chaos toxic/resource remains.
6. Three qualifying repetitions pass for each required Kafka/network,
   PostgreSQL, poison-message, and application-crash scenario.
7. Fault-specific degradation matches ADR-0015; requests do not hang beyond
   documented budgets and independent paths preserve their applicable Phase 8
   SLOs.
8. Applications reconnect, become ready, and drain durable work within
   scenario-specific evidence-backed recovery budgets after network/dependency
   restoration or harness-controlled process restart.
9. ID-based reconciliation proves zero lost accepted Orders, zero partial
   Order/outbox commits, zero duplicate Inventory effects, zero duplicate or
   missing read-model rows, and only the expected poison records in the shared
   DLQ.
10. Cascading-failure experiments show bounded resources and no failure outside
    the expected scope. A containment failure blocks completion and triggers
    ADR-0016/replanning.
11. Evidence explicitly states that the single broker does not prove broker
    failover/leader election and that harness restart is not infrastructure
    self-healing.
12. `make verify`, `make load-smoke`, and `make chaos-smoke` pass from a clean
    checkout; long chaos runs remain opt-in.
13. The evidence report includes control, successful, failed, invalid, aborted,
    and cleanup-failed runs with exact environment and raw artifact references.
14. No forbidden technology, unapproved remediation, secret, generated result
    bundle, destructive residue, or unrelated change is committed.
15. Architecture review and phase review pass before Phase 9 is declared
    complete.

Phase 9 does not advance automatically. If safe injection, retry compliance,
data integrity, recovery, or containment cannot be proven, the phase remains
open and the gap is documented without silent scope expansion.
