# Phase 14 Plan: Multi-Replica Runtime & Kafka High Availability

**Phase:** Phase 14 — Multi-Replica Runtime & Kafka High Availability

**Status:** APPROVED — COMPLETED

**Date:** 2026-08-17

---

## 1. Phase Objective

Evolve the verified Phase 13 two-service platform from a single-instance
runtime and single Kafka broker into the smallest reproducible high-availability
topology that proves:

1. `app` and `order-query` can run as multiple replicas behind health-aware
   ingress routing.
2. Kafka can tolerate loss of one broker while maintaining acknowledged-event
   durability and consumer progress.
3. Outbox workers, consumer groups, caches, graceful shutdown, and event
   ordering remain correct during replica churn and broker leader election.
4. Availability and recovery claims are supported by explicit topology,
   workload, failure-window, and data-reconciliation evidence.

This phase is an infrastructure availability foundation. It does not claim
full-stack 99.9% availability because PostgreSQL, ingress, the Docker host, and
the container runtime remain single failure domains.

---

## 2. Why This Phase Exists

Phases 3 through 13 established application-level distributed-systems
mechanisms: durable outbox publication, at-least-once delivery, idempotent
consumers, CQRS, sagas, bounded retries, DLQs, replay, schema compatibility,
load shedding, caching, out-of-order guards, observability, load testing, and
fault injection.

The actual default topology still runs one instance of each application
service, one Kafka KRaft broker/controller with replication factor 1, and one
PostgreSQL instance. Existing chaos tests prove reachability loss, restart,
and replay; they intentionally do not prove broker failover, replica-level
service continuity, partition-leader election, or rolling deployment behavior.

Phase 12 introduced `FOR UPDATE SKIP LOCKED` for concurrent outbox workers, but
the repository has not yet qualified multiple real `app` containers claiming
work simultaneously. Phase 13 introduced a client limiter whose counters are
local to one process, so its multi-replica semantics must be explicit before
horizontal scale is presented as production-ready.

The next smallest evolution is therefore replicated stateless services plus
highly available messaging. PostgreSQL HA, Kubernetes, and multi-region
deployment are deliberately deferred rather than bundled into an unreviewable
infrastructure rewrite.

---

## 3. Starting Architecture / State

- Two independently deployable Spring Boot services:
  - `app`: Catalog, Order command, Inventory, compensation, transactional
    outbox, caches, storage pruning, load shedding, and per-instance rate limit.
  - `order-query`: Kafka projections, order read model, monotonic version
    guards, local cache, and DLQ replay API.
- Cross-deployable communication uses Kafka events only.
- One PostgreSQL 16 container is the source of truth, with schema ownership
  divided between `app` and `order-query`.
- One Kafka 3.7-compatible KRaft broker/controller runs with replication
  factor 1.
- Compose exposes one fixed `app` port and one fixed `order-query` port.
- The test plane contains k6 scenarios, Toxiproxy, data-reconciliation scripts,
  operational metrics, and failure runbooks.
- Phase 13 is complete and its evidence reports successful stream operations,
  bounded replay, out-of-order protection, and local rate-limit behavior.

---

## 4. Target Architecture / State

```text
                         k6 / external client
                                  |
                         health-aware HAProxy
                         /                 \
                app replica set     order-query replica set
                    (2 or more)          (2 or more)
                       |   \               /   |
                       |    +-------------+    |
                       |          |             |
                       +---- Kafka KRaft -------+
                            3 broker/controllers
                            RF=3, min ISR=2
                                  |
                            PostgreSQL 16
                         single primary (explicit
                         remaining failure domain)
```

- At least two independently addressable replicas of each application service.
- A pinned HAProxy container performs health-aware HTTP routing and removes
  unready or draining replicas.
- A three-node Kafka KRaft cluster provides replicated topics, broker leader
  election, and quorum metadata availability.
- Business topics use at least three partitions where consumer concurrency is
  required, replication factor 3, and minimum in-sync replicas 2.
- Producers use durability settings consistent with the approved Kafka HA
  contract; event keys preserve per-aggregate partition ordering.
- Multi-replica outbox polling and consumer-group behavior are empirically
  qualified during replica termination and restart.
- Rate-limit enforcement has an explicit scope. Phase 14 must prevent ordinary
  round-robin routing from silently multiplying the documented client quota.
- PostgreSQL remains a single primary and is exercised as a documented negative
  control, not presented as highly available.

---

## 5. Problems This Phase Addresses

1. **Application replica loss:** A single process termination currently removes
   the only serving instance of that API.
2. **Single-broker messaging:** The current broker cannot demonstrate replica
   durability, leader election, or continued messaging during broker loss.
3. **Unproved worker coordination:** Database primitives support concurrent
   outbox workers, but container-level multi-worker behavior is unqualified.
4. **Consumer rebalance uncertainty:** Projection correctness and recovery
   latency during real replica churn are not measured.
5. **Rate-limit multiplication:** Per-process counters allow a client routed
   across replicas to receive more than the intended topology-wide allowance.
6. **Overbroad availability language:** Earlier certifications do not prove
   full infrastructure high availability.

---

## 6. Architecture Changes

- Add a dedicated Compose HA profile or overlay with multiple application
  replicas, stable service discovery, unique instance identity, and no fixed
  per-replica host-port assumptions.
- Add HAProxy as the single Phase 14 ingress and health-aware load balancer.
- Replace the single Kafka container in the HA profile with a three-node KRaft
  quorum using deterministic broker IDs, listeners, and persistent volumes.
- Define topic durability and producer acknowledgement settings through an
  explicit bootstrap/configuration mechanism rather than implicit broker
  defaults.
- Establish a Phase 14 ingress rate-limit contract. The preferred minimal
  design is enforcement at the single HAProxy ingress, with the application
  filter retained only as bounded defense in depth. Any alternative requires
  the Phase 14 ADR to explain quota behavior during routing and failover.
- Extend the existing load and chaos plane with allow-listed replica and broker
  failure controls, recovery deadlines, ISR checks, lag checks, and
  reconciliation.
- Preserve PostgreSQL as source of truth and preserve all bounded-context and
  schema-ownership rules.

---

## 7. Technology Changes

### Introduced in Phase 14

- A digest-pinned HAProxy container for test and local HA ingress.
- Multi-node configuration of the already-approved Kafka KRaft technology.
- Docker Compose HA overlays and Docker Engine CLI controls in the test plane.

### Retained

- Kotlin, Spring Boot, Gradle, PostgreSQL 16, Flyway, Kafka, Spring Kafka,
  jOOQ, Spring Data JDBC, Caffeine, Micrometer, Brave, Docker, Docker Compose,
  Testcontainers, ArchUnit, k6, Toxiproxy, and POSIX shell.

### Technology rules

- HAProxy is not an authorization boundary and must not expose administrative
  endpoints publicly.
- Images must be version- and digest-pinned.
- No application module may depend on HAProxy-specific client libraries.
- Kafka durability must not rely on auto-created topics or replication-factor
  defaults.

---

## 8. Non-Functional Requirements

- Loss of one `app` replica must not interrupt critical write availability
  through the Phase 14 ingress after health-check convergence.
- Loss of one `order-query` replica must not interrupt query availability after
  health-check convergence.
- Loss of one Kafka broker must preserve quorum, acknowledged event durability,
  outbox progress or bounded buffering, and consumer recovery.
- Every failure scenario must end with 100% reconciliation of committed orders,
  outbox events, inventory outcomes, and read-model rows after bounded drain.
- Application shutdown must remain graceful; SIGKILL experiments must rely on
  idempotency and redelivery rather than graceful behavior.
- The topology must remain reproducible on the documented local qualification
  environment.
- Availability claims must name excluded failure domains and must not imply
  PostgreSQL, ingress, host, or regional HA.

---

## 9. Performance Expectations

- The no-fault multi-replica topology must continue to satisfy the existing
  critical API p95 target of less than 200ms under the approved qualification
  workload.
- HAProxy must add less than 5ms p95 latency relative to the direct-service
  control run in the same environment.
- During loss of one application replica, critical API p95 must recover below
  200ms within 30 seconds after ingress health convergence.
- During loss of one Kafka broker, `POST /orders` must continue within its
  defined HTTP SLO or buffer durably through the outbox; Kafka publication lag
  must return to the Phase 14 steady-state threshold within 60 seconds of
  quorum stabilization.
- The topology must tolerate the existing defined 5x spike without data loss;
  exact throughput and VU counts must be taken from the approved workload
  contract rather than invented by the implementation task.

---

## 10. Reliability Expectations

- Kafka business topics use replication factor 3 and minimum in-sync replicas
  2 in the Phase 14 HA topology.
- Producers use `acks=all`; unclean leader election is disabled.
- Loss of one broker must not lose records acknowledged under the approved
  durability contract.
- Consumer-group rebalances must not create duplicate domain effects; duplicate
  deliveries remain acceptable only when idempotently suppressed.
- Aggregate event keys must retain per-order partition affinity and ordering.
- Multi-replica outbox workers must claim disjoint batches through existing
  database concurrency controls and must recover abandoned work.
- Failed or draining HTTP replicas must be removed from ingress routing within
  a measured deadline.
- PostgreSQL loss is expected to make write paths unavailable in this phase;
  tests must verify honest health signaling and no corruption, not claim
  database failover.

---

## 11. Observability Requirements

- Every application instance exposes a non-secret instance identifier in logs
  and approved operational metrics so routing and consumer ownership can be
  reconstructed.
- HAProxy exposes health and request metrics needed to observe backend state,
  routing errors, retries, and response status without exposing its admin UI
  publicly.
- Kafka qualification captures broker availability, topic leader, ISR count,
  under-replicated partitions, offline partitions, consumer lag, and rebalance
  duration.
- Existing trace and correlation identifiers must survive ingress proxying,
  Kafka publication, redelivery, and consumer rebalancing.
- Evidence must include a timestamped event sequence for each injected failure,
  detection, routing/leader change, recovery, drain, and reconciliation.
- Alerts and runbooks must distinguish service replica degradation, Kafka
  quorum loss, under-replication, and the still-single PostgreSQL failure mode.

---

## 12. Security Considerations

- Public traffic reaches services only through the Phase 14 ingress in the HA
  topology; direct service ports and HAProxy administrative endpoints remain
  internal to the Compose network or test harness.
- HAProxy must overwrite or sanitize client-controlled forwarding headers.
  Application trust of `X-Forwarded-For` must be limited to the trusted proxy
  path so clients cannot choose another client's quota identity.
- DLQ replay and Actuator endpoints must not be exposed by public ingress
  routing.
- Kafka remains internal-only in this local phase; adding TLS/SASL or workload
  identity is explicitly deferred to a security-focused deployment phase.
- Chaos controls retain exact target allow-lists, confirmation tokens, traps,
  watchdogs, and post-test cleanup verification.
- No credentials, generated certificates, data volumes, or HAProxy stats
  secrets may be committed.

---

## 13. Data Considerations

- PostgreSQL remains the system of record and retains existing per-service
  schema ownership.
- Kafka replication improves transport durability but does not turn Kafka into
  the authoritative business database.
- Topic creation must be deterministic and idempotent, with partitions,
  replication factor, minimum ISR, retention, and cleanup policy recorded.
- Event keys must remain stable across producer retries and replay so all events
  for one aggregate stay on the same partition.
- No cross-schema application queries are introduced. Test-only reconciliation
  continues to query owned schemas independently.
- Broker-volume lifecycle and cleanup must be isolated from PostgreSQL data and
  must not delete user-owned volumes outside the dedicated HA test project.

---

## 14. Explicitly Out-of-Scope Capabilities

- PostgreSQL streaming replicas, automated primary election, managed database
  failover, point-in-time recovery qualification, or multi-zone database HA.
- Kubernetes, Helm, Terraform, cloud-managed PaaS, autoscaling, pod disruption
  budgets, or orchestrator self-healing claims.
- Multi-region or active-active deployment, Kafka cluster linking/MirrorMaker,
  global traffic management, or DNS failover.
- Highly available ingress or VRRP/virtual-IP management; HAProxy is a single
  controlled ingress for this qualification phase.
- Redis or another shared rate-limit/cache store.
- Service mesh, SPIFFE/SPIRE, mTLS rollout, API gateway product adoption, or
  centralized secrets management.
- Database sharding, table partitioning, read replicas, or separate physical
  databases per bounded context.
- New business contexts, additional service extraction, Payment/Shipping SaaS
  integration, synchronous inter-service APIs, XA/2PC, or event sourcing.
- Claiming production-wide 99.9% availability from a bounded local test.

---

## 15. Dependencies on the Previous Phase

- Phase 13 must remain completed with its phase-review evidence accepted.
- Aggregate version guards, bounded DLQ replay, and per-instance client limiter
  tests must remain green.
- Phase 12 `SKIP LOCKED` outbox coordination and cache invalidation behavior are
  prerequisites for real replica qualification.
- Phase 10 graceful shutdown, readiness probes, alerts, and runbooks are reused.
- Phase 9 Toxiproxy safety controls and reconciliation harness are extended,
  not replaced.
- ADR-0006, ADR-0007, ADR-0011, ADR-0012, ADR-0015, ADR-0016, ADR-0020,
  ADR-0021, and ADR-0022 remain binding unless superseded explicitly.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Multi-broker listeners are misconfigured and clients bypass the intended topology | False HA result or broken connectivity | Preflight metadata inspection from every client network and explicit advertised-listener assertions |
| Topic auto-creation produces replication factor 1 | Acknowledged events remain vulnerable to broker loss | Disable or reject implicit topic creation for qualification; assert partitions, RF, ISR, and configs before traffic |
| A broker stop is mistaken for durable failover without checking acknowledgements and ISR | Unsupported zero-loss claim | Record producer acknowledgements, leader/ISR transitions, drain lag, and final reconciliation |
| Compose replica naming or fixed ports prevent horizontal scale | Topology cannot reproduce | Remove fixed container-name/host-port assumptions in the HA overlay and test clean startup twice |
| Rebalances cause duplicate side effects | Inventory or projection corruption | Preserve event IDs, idempotent handlers, stable keys, and reconciliation assertions |
| Round-robin routing multiplies per-process rate limits | Noisy clients exceed the documented quota | Enforce the Phase 14 quota at ingress or document an ADR-approved equivalent and test across backends |
| Forwarded headers can be spoofed | Quota bypass or incorrect audit identity | Ingress overwrites headers; direct service access is not public; add security tests |
| A single HAProxy or PostgreSQL instance is mistaken for full HA | Overstated production readiness | Publish the failure-domain table and execute negative controls showing the remaining limits |
| Three brokers plus replicas exceed developer resources | Flaky or unusable qualification | Define minimum CPU/RAM/disk, add preflight capacity checks, and provide bounded smoke/full profiles |
| Chaos cleanup targets unrelated containers or volumes | User data loss | Dedicated Compose project, exact labels/IDs, confirmation token, traps, watchdog, and non-destructive default cleanup |

---

## 17. ADRs That May Be Required

### Required: ADR-0023 — Multi-Replica Runtime and Kafka HA Strategy

ADR-0023 must be accepted before infrastructure implementation. It must record:

- why Phase 14 addresses replicated stateless services and Kafka before
  PostgreSQL HA or Kubernetes;
- HAProxy selection and alternatives, including direct ports, DNS round robin,
  and introducing an orchestrator now;
- Kafka KRaft quorum size, broker/controller roles, topic partitioning,
  replication factor, minimum ISR, producer acknowledgements, leader-election
  policy, and failure semantics;
- application replica discovery, health routing, graceful drain, and instance
  identity;
- rate-limit ownership and its exact topology-wide semantics;
- remaining single points of failure and forbidden availability claims;
- operational cost, rollback, failure modes, and evidence requirements.

Additional ADRs are not expected unless implementation discovers a need for a
new runtime product, changes PostgreSQL architecture, changes the consistency
model, or expands beyond this phase. Such a discovery stops the task and
requires separate approval.

---

## 18. Ordered Implementation Tasks

### P14-01 — ADR-0023 and HA Qualification Contract (COMPLETED)

- **Objective:** Approve the precise multi-replica and Kafka HA architecture,
  failure domains, workload, durability contract, and claim boundaries before
  infrastructure changes.
- **Context:** Current documents prove application mechanisms on a single-broker,
  single-instance default topology; technology and availability semantics must
  be explicit before adding HAProxy and broker replicas.
- **Dependencies:** Phase 13 completed and reviewed.
- **Scope:** ADR-0023, Phase 14 topology diagram, failure-domain matrix,
  workload contract, environment/resource specification, and documentation
  terminology alignment.
- **Implementation requirements:**
  - Compare HAProxy with no ingress and with immediate orchestrator adoption.
  - Specify Kafka quorum, RF, ISR, acknowledgements, partitions, keys, and
    expected behavior for one-broker and quorum loss.
  - Specify replica counts, health deadlines, graceful-drain behavior, and
    rate-limit ownership.
  - List every remaining single point of failure and prohibited claim.
  - Reconcile Phase 14 thresholds with existing SLO and load documents.
- **Acceptance criteria:**
  - ADR-0023 is accepted through the repository decision process.
  - The topology and failure matrix unambiguously distinguish availability,
    durability, recovery, and data-integrity assertions.
  - PostgreSQL HA, Kubernetes, multi-region, and globally replicated ingress
    are explicitly excluded.
- **Verification requirements:** Documentation review against `AGENTS.md`, the
  constitution, current architecture, relevant ADRs, and Phase 13 evidence;
  verify every planned experiment maps to an invariant and measurable deadline.
- **Expected files/components:** `docs/adr/0023-multi-replica-runtime-and-kafka-ha.md`,
  `docs/architecture.md`, `docs/bootcamp/phase-14-plan.md`, workload/environment
  documentation under `performance/` as approved.
- **Architecture impact:** Authorizes the Phase 14 topology without implementing it.
- **Out of scope:** Compose changes, application changes, broker deployment, or
  executing load/failure tests.

### P14-02 — Deterministic Three-Broker Kafka HA Topology (COMPLETED)

- **Objective:** Provide a reproducible three-node Kafka KRaft quorum with
  explicit durable topic configuration and preflight validation.
- **Context:** The current single broker uses replication factor 1 and cannot
  prove leader failover or acknowledged-event survival.
- **Dependencies:** P14-01.
- **Scope:** Dedicated Compose HA overlay/profile, broker volumes and listeners,
  topic bootstrap/configuration, health checks, and topology preflight scripts.
- **Implementation requirements:**
  - Configure three deterministic KRaft broker/controller nodes.
  - Configure internal/external listeners so metadata never directs clients
    around the intended test or Toxiproxy path.
  - Create business and DLQ topics deterministically with approved partitions,
    RF=3, and min ISR=2.
  - Configure producers for `acks=all` and validate unclean leader election is
    disabled.
  - Assert broker quorum, leaders, replicas, ISR, and topic settings before any
    qualification run.
  - Keep data volumes within the dedicated Compose project.
- **Acceptance criteria:**
  - Clean startup forms a healthy three-node quorum on two consecutive runs.
  - Every required topic has the approved partitions, RF, ISR, and policies.
  - Stopping any one broker leaves metadata quorum and topics available with
    no offline partitions after bounded leader election.
- **Verification requirements:** Run configuration linting, Compose startup,
  preflight assertions, publish/consume smoke tests, one-broker stop/restart,
  and post-recovery ISR verification.
- **Expected files/components:** `compose.ha.yml` or
  `performance/compose.ha.yml`, topic bootstrap scripts, preflight scripts,
  Makefile HA lifecycle targets, and focused infrastructure documentation.
- **Architecture impact:** Changes the Phase 14 event transport from a single
  broker to a replicated Kafka quorum; default developer topology may remain
  lightweight if clearly separated.
- **Out of scope:** Application replicas, PostgreSQL replication, cross-region
  Kafka, TLS/SASL, or performance certification.

### P14-03 — Multi-Replica Services and Health-Aware Ingress (COMPLETED)

- **Objective:** Run at least two replicas of `app` and `order-query` behind a
  health-aware HAProxy ingress with safe forwarding and graceful drain.
- **Context:** Current fixed container names and host ports describe one replica
  per service and cannot prove request continuity during instance loss.
- **Dependencies:** P14-01; may proceed in parallel with P14-02 after the ADR.
- **Scope:** HAProxy configuration, Compose HA service definitions, instance
  identity, readiness routing, public/internal port boundaries, forwarded
  headers, and replica lifecycle tests.
- **Implementation requirements:**
  - Remove fixed naming and per-replica host-port assumptions from the HA
    topology without breaking the default developer profile.
  - Route public command/catalog and order-query paths to the correct pools.
  - Remove unready/draining backends within the ADR deadline.
  - Preserve correlation IDs and expose instance identity in approved logs and
    metrics.
  - HAProxy overwrites untrusted forwarding headers; admin and Actuator routes
    remain internal or explicitly protected.
  - Prove graceful SIGTERM drain and forced SIGKILL recovery separately.
- **Acceptance criteria:**
  - Requests are distributed across at least two healthy replicas per service.
  - Loss of one replica leaves its API reachable through ingress after bounded
    health convergence.
  - No public route exposes DLQ replay, HAProxy administration, or unrestricted
    Actuator endpoints.
- **Verification requirements:** Configuration lint, routing distribution test,
  readiness removal test, SIGTERM drain test, SIGKILL failover test, header
  spoofing test, and existing service integration tests.
- **Expected files/components:** HAProxy configuration, Compose HA overlay,
  service configuration for instance identity/proxy handling, test scripts,
  and ingress runbook.
- **Architecture impact:** Adds horizontally replicated stateless service pools
  and a single controlled ingress to the Phase 14 topology.
- **Out of scope:** Ingress HA, Kubernetes Services/Ingress, autoscaling,
  application-domain changes, or Kafka broker qualification.

### P14-04 — Multi-Replica Event Processing and Ordering Qualification (COMPLETED)

- **Objective:** Verify concurrent outbox workers, partitioned consumers,
  rebalances, cache behavior, and aggregate ordering across real service
  replicas.
- **Context:** Phase 12 proved database claim primitives and Phase 13 proved
  projection version guards, but not container-level replica churn.
- **Dependencies:** P14-02 and P14-03.
- **Scope:** Topic partition/key assertions, outbox worker identity and metrics,
  consumer-group ownership tests, duplicate suppression, abandoned-work
  recovery, local-cache consistency, and reconciliation.
- **Implementation requirements:**
  - Verify order aggregate IDs produce stable Kafka keys and partition affinity.
  - Run simultaneous `app` outbox workers and prove disjoint claims plus
    eventual recovery of interrupted batches.
  - Run multiple projection consumers and record partition ownership before,
    during, and after rebalance.
  - Kill an active worker and an active consumer separately under traffic.
  - Verify duplicates are idempotently suppressed and later aggregate versions
    cannot be overwritten by stale deliveries.
  - Verify local caches converge through existing event invalidation or bounded
    TTL behavior after writes and replica changes.
- **Acceptance criteria:**
  - No duplicate inventory reservation or read-model row is created.
  - All committed events are eventually published and projected after bounded
    drain.
  - Aggregate ordering and monotonic read-model state hold across rebalances.
  - Final cross-schema reconciliation is 100%.
- **Verification requirements:** Focused integration tests plus an external
  multi-replica scenario with database queries, Kafka group/partition evidence,
  metric assertions, and repeatable failure timing.
- **Expected files/components:** Multi-replica integration/qualification tests,
  performance scripts, reconciliation extensions, metrics, and evidence hooks.
- **Architecture impact:** Validates that existing application-level
  concurrency and idempotency mechanisms remain correct when physically
  replicated.
- **Out of scope:** New event types, business workflow changes, exactly-once
  transactions, or database HA.

### P14-05 — Topology-Wide Client Rate-Limit Enforcement (COMPLETED)

- **Objective:** Prevent normal multi-replica routing from multiplying the
  documented client quota while preserving Phase 13 defense-in-depth behavior.
- **Context:** Phase 13 counters live in each `app` JVM. Round-robin requests can
  consume one full quota per replica, so the existing limiter is not a shared
  distributed quota.
- **Dependencies:** P14-01 and P14-03.
- **Scope:** ADR-approved ingress enforcement, application fallback limits,
  trusted client identity, response headers, metrics, and multi-backend tests.
- **Implementation requirements:**
  - Enforce the documented Phase 14 quota at the single HAProxy ingress or use
    the explicitly approved ADR alternative.
  - Preserve HTTP 429 and `Retry-After` behavior.
  - Treat application-local limiting as defense in depth with semantics that do
    not conflict with ingress enforcement.
  - Sanitize spoofed IP/API-key forwarding paths and avoid logging secret API
    keys.
  - Expose bounded-cardinality rate-limit metrics without client identifiers as
    labels.
- **Acceptance criteria:**
  - A client cannot multiply its quota by being routed across different app
    replicas in the Phase 14 topology.
  - Conformant clients continue through different healthy backends.
  - Failure or restart of an application replica does not reset the ingress
    quota; HAProxy restart semantics are explicitly documented as a remaining
    limitation.
- **Verification requirements:** Multi-replica black-box quota test, backend
  distribution assertion, header-spoof test, failover test, latency-overhead
  measurement, and existing Phase 13 limiter unit tests.
- **Expected files/components:** HAProxy rate-limit configuration, application
  configuration if needed, tests, metrics, and rate-limit documentation.
- **Architecture impact:** Moves topology-wide admission ownership to the
  Phase 14 ingress while retaining bounded application-local protection.
- **Out of scope:** Redis, globally replicated quotas, multi-ingress consistency,
  billing quotas, or user-account authorization.

### P14-06 — Broker and Replica Failure Experiment Harness (COMPLETED)

- **Objective:** Extend the safe chaos harness with deterministic application
  replica and Kafka broker/leader failure scenarios under sustained traffic.
- **Context:** Existing Phase 9 scenarios target a single broker and explicit
  harness restarts; Phase 14 needs quorum-aware experiments without expanding
  blast radius.
- **Dependencies:** P14-02 through P14-05.
- **Scope:** HA preflight, exact replica/broker targeting, leader discovery,
  stop/kill/restart operations, failure timelines, cleanup, negative controls,
  and reconciliation gates.
- **Implementation requirements:**
  - Discover exact Compose project resources and Kafka leaders before action.
  - Test one `app` replica loss, one `order-query` replica loss, graceful rolling
    restart, active Kafka leader loss, non-leader broker loss, and broker rejoin.
  - Test Kafka quorum loss only as a bounded negative control; require durable
    outbox buffering and recovery rather than availability.
  - Test PostgreSQL loss only as a negative control documenting the remaining
    single point of failure and honest readiness behavior.
  - Retain confirmation tokens, allow-lists, traps, watchdogs, and post-cleanup
    topology verification.
- **Acceptance criteria:**
  - Every scenario has a no-fault control, explicit hypothesis, deadline,
    invariant, and deterministic cleanup.
  - One-broker and one-application-replica failures meet their approved
    continuity and recovery expectations.
  - Negative controls fail only in the documented ways and recover without
    committed-data corruption.
- **Verification requirements:** Shell/config lint where available, chaos
  preflight, smoke execution of every target type, forced interruption cleanup
  test, and full scenario execution before phase completion.
- **Expected files/components:** `performance/chaos/` scripts and manifests,
  Compose HA/chaos overlays, Makefile targets, reconciliation extensions, and
  runbooks.
- **Architecture impact:** Adds test-plane coverage for real replica and Kafka
  quorum failure modes; no production fault hooks enter application code.
- **Out of scope:** Random production chaos, host/kernel faults, disk corruption,
  clock skew, multi-zone faults, or automatic orchestrator healing.

### P14-07 — HA Load, Recovery, and Availability Qualification (COMPLETED)

- **Objective:** Measure the multi-replica topology under nominal load, the
  approved 5x spike, replica loss, and single-broker loss while preserving
  latency and data integrity.
- **Context:** Functional failover without sustained traffic does not establish
  usable availability or reveal rebalance and retry amplification.
- **Dependencies:** P14-04 through P14-06.
- **Scope:** Control run, resource baseline, steady load, 5x spike, fault
  windows, recovery windows, availability calculation, Kafka lag/ISR evidence,
  and final reconciliation.
- **Implementation requirements:**
  - Reuse the approved Phase 8 workload definitions and critical API SLOs.
  - Compare direct/single-instance historical evidence only when environment
    differences are disclosed; use a same-run HA no-fault control as primary.
  - Record request success, p50/p95/p99, throughput, backend distribution,
    connection-pool use, outbox lag, consumer lag, DLQ count, leader/ISR state,
    JVM/container resources, and recovery time.
  - Calculate observed availability only for the bounded run and classify
    planned fault responses separately from unexpected errors.
  - Drain all asynchronous work before 100% reconciliation.
- **Acceptance criteria:**
  - No-fault and post-recovery critical API p95 remain below 200ms.
  - Single application replica loss and single Kafka broker loss satisfy the
    P14-01 availability and recovery contracts.
  - The approved 5x spike recovers within its existing SLO.
  - Zero unexpected DLQ messages, zero unpublished outbox residue after drain,
    and 100% data reconciliation.
  - Results explicitly avoid a production-wide 99.9% claim.
- **Verification requirements:** Execute the pinned black-box scenarios from a
  clean HA topology, archive machine-readable summaries and configuration,
  independently run reconciliation, and repeat any invalid run from clean state.
- **Expected files/components:** k6 HA scenario, runner scripts, metric/resource
  snapshots, result summaries under `docs/bootcamp/evidence/`, and Makefile
  qualification target.
- **Architecture impact:** Produces empirical capacity and recovery evidence for
  the Phase 14 topology without changing domain architecture.
- **Out of scope:** Cloud cost modeling, multi-host load generation unless
  evidence proves it necessary, autoscaling, or extrapolation beyond the tested
  environment.

### P14-08 — Runbooks, Evidence Dossier, and Phase Review (COMPLETED)

- **Objective:** Consolidate evidence, update operational guidance and living
  architecture documents, and execute formal Phase 14 verification and review.
- **Context:** Infrastructure HA claims are valid only when operators can detect,
  diagnose, and recover the tested failures and when remaining limitations are
  prominent.
- **Dependencies:** P14-01 through P14-07.
- **Scope:** Kafka broker-loss and ISR runbook, replica-drain/rebalance runbook,
  rate-limit ownership documentation, failure-domain matrix, evidence dossier,
  architecture/current-phase updates, task verification, architecture review,
  and phase review.
- **Implementation requirements:**
  - Record exact commands, expected signals, decision points, rollback, and
    escalation conditions for supported failures.
  - Link every exit criterion to reproducible evidence.
  - Record environment, image digests, configuration, workload, timing,
    metrics, logs, reconciliation results, and known limitations.
  - Run the `verify-task`, `architecture-review`, and `phase-review` procedures
    as applicable; resolve or explicitly block on material failures.
  - Advance `current-phase.md` only after the phase review passes.
- **Acceptance criteria:**
  - Required runbooks and Phase 14 evidence dossier are complete and internally
    consistent.
  - Formal verification finds no unresolved acceptance, architecture, security,
    or data-integrity failure.
  - Living architecture documents identify PostgreSQL, ingress, host, and region
    as remaining failure domains.
- **Verification requirements:** Full documentation link/path check, clean
  `make verify`, HA smoke and qualification targets, chaos cleanup verification,
  security checks, complete diff review, and formal phase review.
- **Expected files/components:** `docs/bootcamp/evidence/p14-high-availability.md`,
  HA runbooks, `docs/architecture.md`, `docs/bootcamp/current-phase.md`, and
  review reports.
- **Architecture impact:** Closes Phase 14 only after evidence supports its
  bounded claims; does not authorize Phase 15.
- **Out of scope:** Implementing PostgreSQL HA, Kubernetes/cloud deployment, or
  automatically planning/starting the next phase.

---

## 19. Task Acceptance-Criteria Matrix

| Task | Required acceptance outcome |
|---|---|
| P14-01 | ADR-0023 accepted; topology, failure matrix, durability settings, rate-limit ownership, and claim boundaries approved |
| P14-02 | Three-broker quorum starts reproducibly; topics have approved RF/ISR; one broker can fail without offline partitions |
| P14-03 | At least two replicas per service receive traffic; one replica can fail without API loss after health convergence |
| P14-04 | Outbox and consumers remain idempotent and ordered through churn; 100% reconciliation |
| P14-05 | Client quota cannot be multiplied by replica routing; forwarding identity is trusted and tested |
| P14-06 | Safe deterministic broker/replica experiments and negative controls execute and clean up |
| P14-07 | SLO, recovery, spike, durability, lag-drain, and reconciliation thresholds pass under the approved workload |
| P14-08 | Runbooks, evidence, verification, architecture review, and phase review pass with limitations documented |

No task is accepted on documentation assertions alone when its criteria require
runtime behavior.

---

## 20. Verification Requirements for Every Task

Every implementation task must:

1. Re-read `AGENTS.md`, `docs/constitution.md`,
   `docs/bootcamp/current-phase.md`, this plan, and its task definition.
2. Confirm P14-01 and all declared dependencies are approved and complete.
3. Inspect the existing worktree and preserve unrelated user changes.
4. Implement only the task scope and avoid future-phase technologies.
5. Run the focused unit, integration, configuration, infrastructure, security,
   load, or failure tests named by the task.
6. Run `make verify` unless the task is documentation-only; document any
   additional required HA target.
7. Capture commands, environment, image versions/digests, observed metrics,
   failure timing, and reconciliation evidence.
8. Inspect `git status` and the complete task diff; confirm no secrets,
   unrelated edits, or unintended generated files.
9. Use the task-verification process and record failures through the repository
   issue procedure where applicable.
10. Stop after the assigned task; do not automatically begin its dependents.

---

## 21. Phase Exit Criteria

Phase 14 is complete only when:

1. ADR-0023 is accepted and fully reflected in implementation and operations.
2. The HA profile reproducibly starts at least two `app` replicas, at least two
   `order-query` replicas, one health-aware HAProxy ingress, and three Kafka
   KRaft broker/controllers.
3. Required Kafka topics have approved partitions, replication factor 3,
   minimum ISR 2, durable producer acknowledgements, and stable aggregate keys.
4. Loss of one application replica preserves API availability after bounded
   ingress convergence, and graceful rolling restarts drop no accepted critical
   requests.
5. Loss of any one Kafka broker preserves quorum and acknowledged records;
   leader election, ISR recovery, outbox drain, and consumer catch-up complete
   within approved deadlines.
6. Concurrent outbox workers and consumer replicas produce no duplicate domain
   effects, stale projection regression, or stranded committed event.
7. The documented client quota cannot be multiplied by ordinary routing across
   `app` replicas in the Phase 14 topology.
8. The approved no-fault, replica-loss, broker-loss, and 5x-spike qualification
   runs meet latency, recovery, and error expectations.
9. Every successful run ends with zero unexpected DLQ events, zero unpublished
   outbox residue after drain, and 100% cross-schema reconciliation.
10. Security verification confirms trusted forwarding identity and no public
    exposure of administrative or sensitive operational endpoints.
11. Runbooks and observability cover replica degradation, broker loss,
    under-replication, rebalance, recovery, and negative controls.
12. The evidence dossier names PostgreSQL, HAProxy, Docker host/runtime, and
    region as remaining single failure domains and makes no production-wide
    99.9% availability claim.
13. `make verify`, all Phase 14 HA qualification targets, architecture review,
    task verification, and phase review pass.
14. The complete diff is reviewed, contains no secrets or unrelated changes,
    and Phase 14 is not marked complete until the formal phase review succeeds.

---

## Dependency Graph

```text
P14-01 ──+──> P14-02 ──+
         |             +──> P14-04 ──+
         +──> P14-03 ──+             |
                |       \            +──> P14-06 ──> P14-07 ──> P14-08
                +────────> P14-05 ───+
```

Required execution order:

1. P14-01 first.
2. P14-02 and P14-03 may proceed independently after P14-01.
3. P14-04 requires P14-02 and P14-03.
4. P14-05 requires P14-03 and the rate-limit decision from P14-01.
5. P14-06 requires the implemented topology and behavior from P14-02 through
   P14-05.
6. P14-07 requires the safe failure harness from P14-06.
7. P14-08 is the final documentation, verification, and review gate.
