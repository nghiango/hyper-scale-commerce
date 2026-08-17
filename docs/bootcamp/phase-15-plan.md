# Phase 15 Plan: PostgreSQL High Availability, Fencing & Disaster Recovery

**Phase:** Phase 15 — PostgreSQL High Availability, Fencing & Disaster Recovery

**Status:** COMPLETED

**Date:** 2026-08-17

---

## 1. Phase Objective

Remove PostgreSQL as the remaining single persistent-data failure domain in the
completed Phase 14 topology by implementing and verifying:

1. A three-node PostgreSQL 16 streaming-replication cluster with consensus-based
   primary ownership and automatic failover.
2. Strict split-brain prevention, primary fencing, and a zero-loss acknowledgement
   contract for a single database-node failure.
3. Application and migration connectivity that automatically follows the writable
   primary without bypassing schema ownership.
4. Physical backup, continuous WAL archiving, restore, and point-in-time recovery.
5. Reproducible failover, recovery, load, and reconciliation evidence with explicit
   RPO, RTO, and remaining failure domains.

Phase 15 remains a Docker Compose qualification phase. It does not introduce
Kubernetes, multi-host placement, multi-zone storage, or multi-region replication.

---

## 2. Why This Phase Exists

Phase 14 proved two application replicas per service, health-aware ingress, a
three-broker Kafka quorum, concurrent outbox processing, consumer rebalancing,
topology-wide rate limiting, and data reconciliation. Its negative control also
proved that loss of the single PostgreSQL primary makes both services unavailable.

PostgreSQL is the constitutional source of truth for acknowledged orders,
idempotency records, inventory reservations, and query projections. Kafka
replication cannot recover a database transaction that was acknowledged but not
durably replicated. Conversely, automatic promotion without consensus and
fencing can create two writable primaries and corrupt the source of truth.

Database replication, leader election, fencing, backup, WAL retention, and PITR
form one cohesive reliability boundary and require a dedicated phase. Kubernetes
is deferred so these database semantics can be understood and measured before an
orchestrator adds another control plane.

---

## 3. Starting Architecture / State

- Phase 14 is approved and complete.
- Two `app` and two `order-query` replicas run behind one HAProxy ingress.
- Kafka runs as three KRaft broker/controllers with RF=3, min ISR=2, and
  `acks=all` producer durability.
- Every application replica connects to one PostgreSQL hostname and port.
- PostgreSQL 16 runs as one primary container with one persistent volume.
- PostgreSQL termination makes application readiness fail and stops writes and
  fresh reads; no database promotion exists.
- Existing scripts assume a single PostgreSQL container for seed, reset, and
  cross-schema reconciliation.
- Existing PostgreSQL outage tests verify restart recovery, not replication,
  promotion, fencing, backup restoration, or PITR.
- HAProxy ingress and all containers remain on one Docker host.

---

## 4. Target Architecture / State

```text
                         HAProxy HTTP ingress
                           /             \
                    app replicas     query replicas
                         \               /
                          multi-host JDBC URL
                      targetServerType=primary
                       /        |        \
                 postgres-1 postgres-2 postgres-3
                    primary  sync replica  replica
                       \        |        /
                         Patroni agents
                              |
                       3-node etcd DCS
                              |
                  leader lease + topology state

        PostgreSQL WAL archive ----> pgBackRest repository volume
                                           |
                                isolated restore/PITR target
```

- Three PostgreSQL 16 nodes are managed by Patroni.
- A three-member etcd cluster provides an odd-sized distributed configuration
  store and leader lease; etcd is not business-data storage.
- Patroni uses strict synchronous mode with at least one synchronous standby for
  the single-node failure contract.
- PostgreSQL JDBC uses an ordered multi-host URL with
  `targetServerType=primary`; applications and Flyway discover the writable node
  without a single database proxy.
- pgBackRest stores encrypted-at-rest or access-restricted physical backups and
  archived WAL in a dedicated local repository volume.
- Automatic failover, old-primary fencing, rejoin, backup restore, and PITR are
  qualified under the Phase 14 application/Kafka HA workload.

---

## 5. Problems This Phase Addresses

1. **Single database primary:** One container or volume failure stops the entire
   business data plane.
2. **No failover authority:** There is no consensus system that can safely choose
   exactly one writable primary.
3. **Split-brain risk:** Naive promotion can allow diverging writes on multiple
   nodes.
4. **Static application endpoint:** Current JDBC configuration assumes one host.
5. **Undefined acknowledgement durability:** The relationship between commit
   acknowledgement and standby persistence is not specified.
6. **No operational restore proof:** Backups, WAL archiving, PITR, and restore
   duration are not empirically verified.
7. **Single-container tooling assumptions:** Seed, reset, reconciliation, and
   failure scripts directly target one container rather than the current primary.

---

## 6. Architecture Changes

- Introduce Patroni-managed PostgreSQL nodes and a three-member etcd DCS in a
  dedicated Phase 15 Compose overlay.
- Configure physical streaming replication, replication slots, WAL retention,
  strict synchronous replication, and controlled replica reinitialization.
- Change database clients to use an explicitly configured multi-host JDBC URL
  that discovers only the writable primary.
- Ensure Flyway migrations execute safely against the primary and retain separate
  migration histories and schema ownership for each deployable.
- Add pgBackRest physical backups and continuous WAL archiving to a dedicated
  repository volume.
- Extend failure tooling with primary discovery, lease/quorum checks, exact-node
  targeting, fencing assertions, promotion timing, rejoin validation, restore
  isolation, and reconciliation.
- Preserve Kafka, outbox, CQRS, event contracts, domain boundaries, and public API
  behavior.

---

## 7. Technology Changes

### Introduced in Phase 15

- Patroni for PostgreSQL topology management and primary election.
- etcd as the Patroni distributed configuration store, deployed as three
  digest-pinned members.
- pgBackRest for physical backup, WAL archiving, restore, and PITR qualification.
- PostgreSQL JDBC multi-host primary discovery.

### Retained

- PostgreSQL 16, Flyway, Spring Boot, HikariCP, jOOQ, Spring Data JDBC, Kafka,
  HAProxy, Docker Compose, k6, Toxiproxy, Micrometer, and the Phase 14 HA harness.

### Technology rules

- Exact Patroni, etcd, and pgBackRest images or packages must be pinned by version
  and digest before implementation.
- etcd may contain only Patroni control-plane state, never business data or
  application configuration unrelated to database HA.
- Database credentials, replication credentials, repository keys, and generated
  configuration secrets must not be committed.
- No cloud-managed database or object store is introduced.

---

## 8. Non-Functional Requirements

- One PostgreSQL node loss must not lose any transaction acknowledged under the
  Phase 15 synchronous durability contract.
- Automatic primary recovery objective: RTO at most 30 seconds from confirmed
  primary unavailability to successful new write through the public API.
- Single-node-failure RPO: zero acknowledged transactions.
- If no synchronous standby is available, the system must reject or block new
  commits rather than silently weaken the durability contract.
- Old-primary fencing must be proved before or as part of promotion; two writable
  primaries are never acceptable.
- Every failover or restore scenario must finish with 100% cross-schema
  reconciliation after asynchronous drain.
- Backup and restore procedures must be reproducible without mutating the active
  qualification cluster.
- Availability claims remain bounded to one Docker host and exclude ingress,
  host, zone, and region loss.

---

## 9. Performance Expectations

- Critical API p95 remains below 200ms in the no-fault synchronous-replication
  topology under the approved Phase 14 workload.
- Synchronous replication overhead is measured against a same-run single-primary
  control and documented; no unsupported sub-millisecond target is invented.
- During primary loss, failed requests are bounded to the detection/promotion and
  connection-recovery window; p95 returns below 200ms within 60 seconds after a
  writable primary is available.
- Outbox and projection lag return to their approved steady-state thresholds
  within 120 seconds after database failover.
- Full backup and PITR restore throughput, duration, and storage amplification are
  measured for the qualification dataset.

---

## 10. Reliability Expectations

- Patroni holds one valid leader lease and exposes exactly one writable primary.
- etcd retains quorum after loss of one member; etcd quorum loss prevents unsafe
  promotion and triggers the documented conservative behavior.
- At least one synchronous standby confirms each acknowledged commit.
- Replication slots and WAL retention cannot grow without bounds; lag and disk
  thresholds have explicit alerts and operator actions.
- A failed primary is demoted or isolated before rejoining as a replica.
- Replica divergence or rewind requirements are detected; automated `pg_rewind`
  or controlled reinitialization follows the approved ADR.
- Applications recover stale pooled connections and discover the new primary
  without restart or manual URL changes.
- Backup manifests and checksums are verified before a restore is considered valid.
- PITR restores to an isolated target and never overwrites the active cluster.

---

## 11. Observability Requirements

- Expose or collect Patroni role, leader, timeline, failover, and restart metrics.
- Record etcd member health, quorum, leader changes, database lease state, and
  request latency.
- Monitor PostgreSQL replication lag in bytes and seconds, WAL generation and
  retention, replication slots, timeline changes, checkpoint pressure, and disk
  usage.
- Monitor backup age, last successful full/differential backup, archive success,
  WAL archive backlog, restore duration, and repository capacity.
- Existing application health must distinguish database connection failure from
  read-only/wrong-primary routing.
- Every experiment records a timestamped sequence: fault, detection, fencing,
  election, promotion, client reconnection, async drain, and reconciliation.
- Alerts must route operators to failover, replication-lag, WAL-disk, backup, and
  restore runbooks.

---

## 12. Security Considerations

- Use separate least-privilege roles for application schemas, replication,
  Patroni administration, backup, and restore.
- `pg_hba.conf` must allow only required cluster and application paths; broad
  trust authentication is forbidden outside an isolated test fixture.
- Patroni and etcd management APIs remain internal to the Compose network and are
  not exposed through public HAProxy routes.
- etcd client/peer authentication and transport protection must be evaluated in
  ADR-0024; any local plaintext exception must be isolated and explicitly barred
  from production claims.
- Backup repositories must not contain plaintext committed credentials and must
  have restricted filesystem ownership.
- Restored data is treated as production-sensitive data; test fixtures must use
  synthetic data and cleanup must avoid unrelated volumes.
- Failure scripts retain exact resource allow-lists, confirmation tokens,
  watchdogs, traps, and non-destructive defaults.

---

## 13. Data Considerations

- PostgreSQL remains the only business source of truth.
- Schema ownership does not change: `app` owns `catalog`, `order`, and
  `inventory`; `order-query` owns `order_query`.
- Physical replication copies the entire PostgreSQL cluster; it does not grant
  one service permission to access another service's schemas.
- The Phase 15 synchronous commit policy applies to business writes, outbox rows,
  idempotency keys, inventory reservations, and read-model writes.
- Backup retention, WAL retention, restore points, and repository pruning must be
  explicit and bounded.
- Reconciliation after promotion must include order rows, outbox publication,
  inventory outcomes, read-model rows, idempotency behavior, timelines, and DLQs.
- A PITR exercise must prove both the presence of data before the chosen recovery
  point and the absence of a sentinel transaction after it.

---

## 14. Explicitly Out-of-Scope Capabilities

- Kubernetes, Helm, operators, cloud-managed databases, autoscaling, pod
  disruption budgets, or orchestrator self-healing.
- Multi-host, multi-zone, multi-region, active-active, or geo-distributed
  PostgreSQL.
- Logical replication for business integration, database sharding, table
  partitioning, read scaling, or changing the write consistency model.
- Highly available public ingress; Phase 14 HAProxy remains one container.
- Cloud object storage, external secrets managers, HSMs, service mesh, or
  SPIFFE/SPIRE.
- Kafka topology redesign, new event types, new business contexts, service
  extraction, synchronous inter-service APIs, XA/2PC, or event sourcing.
- Claiming production-wide 99.9% availability from the local Compose topology.

---

## 15. Dependencies on the Previous Phase

- Phase 14 must remain approved and complete with accepted evidence.
- ADR-0023, multi-replica ingress, Kafka RF=3/min ISR=2, aggregate keying,
  concurrent outbox claims, and reconciliation must remain intact.
- Existing PostgreSQL outage negative controls provide the starting failure case.
- Existing database pool hardening and readiness behavior are reused.
- Phase 14 application/Kafka load and chaos targets provide regression controls.
- ADR-0007, ADR-0011, ADR-0012, ADR-0015, ADR-0016, ADR-0021, and ADR-0023 remain
  binding unless ADR-0024 explicitly supersedes a database-related decision.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Unsafe promotion creates two writable primaries | Irreconcilable data divergence | Consensus leader lease, strict fencing assertions, partition tests, and immediate test failure on multiple primaries |
| Synchronous standby loss stalls all writes | Availability loss | Explicit consistency-over-availability contract, health/lag alerts, fast standby recovery, and negative-control tests |
| JDBC clients remain pinned to the old primary | Extended outage | Multi-host primary discovery, bounded pool lifetimes, connection validation, and black-box reconnection tests |
| etcd quorum is confused with business-data durability | False safety claim | Document DCS purpose; reconcile PostgreSQL WAL/data independently |
| WAL retention fills disk during replica outage | Cluster-wide database failure | Bounded slots, lag/disk alerts, operator thresholds, and controlled reinitialization |
| Automatic rewind or reinit destroys the only good copy | Data loss | Verify timelines/backups, require safe preconditions, and test on dedicated volumes |
| Backup exists but cannot restore | False DR readiness | Scheduled restore verification, checksum validation, isolated PITR exercise |
| Flyway runs against a replica or concurrently during failover | Migration corruption | Primary-only JDBC contract, migration lock verification, and failover exclusion during migrations |
| Resource footprint destabilizes Kafka/app tests | Invalid evidence | Minimum environment preflight and separate smoke/full profiles |
| Cleanup removes user-owned database volumes | Irrecoverable local data loss | Dedicated Compose project/volumes, exact labels, confirmation token, and no destructive wildcard cleanup |

---

## 17. ADRs That May Be Required

### Required: ADR-0024 — PostgreSQL HA, Fencing, Backup & Recovery Strategy

ADR-0024 must be accepted before topology implementation and must decide:

- Patroni versus native/manual promotion and other evaluated alternatives;
- etcd quorum size, lease behavior, DCS failure semantics, and management API
  protection;
- strict synchronous replication, acknowledgement contract, RPO, RTO, and
  consistency-over-availability behavior;
- primary discovery through multi-host JDBC versus database proxy alternatives;
- fencing, rewind, rejoin, replica rebuild, and timeline divergence procedures;
- replication slots, WAL retention, disk-pressure boundaries, and alerts;
- pgBackRest backup types, repository protection, retention, restore, and PITR;
- migration behavior during failover and rolling application deployment;
- remaining host, ingress, zone, and regional failure domains.

No additional ADR is expected unless implementation introduces a different DCS,
database proxy, storage system, consistency model, or cloud service. Such a need
stops the affected task and requires approval.

---

## 18. Ordered Implementation Tasks

### P15-01 — ADR-0024 and Database Reliability Contract (COMPLETED)

- **Objective:** Approve the database HA architecture, durability contract,
  failure model, RPO/RTO, fencing, and recovery boundaries before implementation.
- **Context:** Phase 14 deliberately left PostgreSQL as the remaining business-data
  single point of failure.
- **Dependencies:** Completed Phase 14 review and evidence.
- **Scope:** ADR-0024, topology and failure-domain diagrams, commit/durability
  contract, workload/environment contract, recovery matrix, and claim boundaries.
- **Implementation requirements:** Evaluate Patroni, manual/native promotion, DCS
  alternatives, JDBC/proxy routing, synchronous policy, backup tooling, fencing,
  and operational cost; define measurable failover and restore hypotheses.
- **Acceptance criteria:** ADR-0024 is accepted; exactly-one-primary invariant,
  RPO/RTO, no-standby behavior, restore criteria, and prohibited claims are
  unambiguous.
- **Verification requirements:** Documentation review against the constitution,
  Phase 14 evidence, PostgreSQL ownership rules, and every planned failure case.
- **Expected files/components:** `docs/adr/0024-postgresql-ha-fencing-and-recovery.md`,
  architecture diagrams, workload/environment specification, and this plan if
  the accepted decision requires bounded amendments.
- **Architecture impact:** Authorizes a replicated source-of-truth topology; no
  runtime change occurs in this task.
- **Out of scope:** Compose implementation, application changes, backups, or test
  execution.

### P15-02 — Patroni, etcd, and PostgreSQL Replication Topology (COMPLETED)

- **Objective:** Build a deterministic three-node PostgreSQL/Patroni cluster with
  three-member etcd quorum and strict synchronous replication.
- **Context:** Automatic failover is unsafe without consensus, role visibility,
  replication configuration, and fenced membership.
- **Dependencies:** P15-01.
- **Scope:** Phase 15 Compose overlay, PostgreSQL/Patroni/etcd configuration,
  credentials injection, volumes, health checks, bootstrap, and topology preflight.
- **Implementation requirements:** Pin images; configure one primary, two
  standbys, strict synchronous mode, slots/WAL bounds, Patroni lease and REST
  health, etcd quorum, unique volumes, and exact topology assertions.
- **Acceptance criteria:** Clean startup twice yields exactly one writable primary,
  at least one synchronous standby, one additional healthy replica, and healthy
  etcd quorum; a test transaction appears on the required replicas.
- **Verification requirements:** Configuration lint, two clean bootstrap runs,
  role/quorum/replication assertions, commit visibility test, one-etcd-member loss,
  and non-destructive cleanup verification.
- **Expected files/components:** Phase 15 Compose overlay, Patroni/PostgreSQL/etcd
  configurations, bootstrap and preflight scripts, Makefile lifecycle targets.
- **Architecture impact:** Replaces the Phase 15 single database container with a
  consensus-managed replicated database cluster.
- **Out of scope:** Application routing, failover qualification, backup/PITR, or
  Kubernetes.

### P15-03 — Primary-Aware Application Connectivity and Migration Safety (COMPLETED)

- **Objective:** Make all application replicas and Flyway migrations discover and
  recover to the current writable primary without manual reconfiguration.
- **Context:** Existing JDBC URLs contain one host and pools may retain dead or
  read-only connections after promotion.
- **Dependencies:** P15-02.
- **Scope:** Configurable multi-host JDBC URLs, primary selection, Hikari recovery,
  readiness semantics, Flyway primary-only behavior, scripts that discover the
  primary, and focused tests.
- **Implementation requirements:** Add an explicit JDBC URL override; use
  `targetServerType=primary`; bound connection acquisition/revalidation; reject
  writes to replicas; preserve per-service Flyway histories and schema ownership;
  remove single-container assumptions from Phase 15 tooling.
- **Acceptance criteria:** Both services start against the cluster, migrations run
  once against the primary, replica endpoints reject writes, and pools reconnect
  to a promoted primary without process restart.
- **Verification requirements:** Unit/config tests, integration startup, concurrent
  migration test, wrong-primary test, controlled promotion/reconnection smoke, and
  existing database/architecture tests.
- **Expected files/components:** Application datasource configuration, Compose
  environment, primary-discovery helpers, integration tests, and operator notes.
- **Architecture impact:** Changes connection discovery, not domain persistence or
  data ownership.
- **Out of scope:** Full chaos/load qualification, backup implementation, read
  routing, or schema redesign.

### P15-04 — Failover, Fencing, Rejoin, and Split-Brain Qualification (COMPLETED)

- **Objective:** Prove safe automatic promotion, exactly-one-primary behavior,
  old-primary fencing, and controlled node rejoin across supported failures.
- **Context:** Replication alone does not prevent split brain or establish usable
  failover recovery.
- **Dependencies:** P15-02 and P15-03.
- **Scope:** Primary kill, network isolation, etcd member loss/quorum loss,
  synchronous-standby loss, old-primary return, rewind/reinit, timelines, safe
  failure harness, and reconciliation.
- **Implementation requirements:** Discover roles before faults; target exact
  resources; assert fencing and leader lease; measure promotion/client recovery;
  test conservative behavior without DCS quorum or sync standby; validate rejoin
  and replication catch-up; preserve chaos safety controls.
- **Acceptance criteria:** Single-primary loss recovers within RTO with RPO=0 for
  acknowledged writes; at no time are two writable primaries observed; returning
  nodes rejoin on the current timeline; negative controls fail safely.
- **Verification requirements:** Repeatable no-fault control, primary kill,
  partition, standby loss, DCS quorum-loss, and rejoin scenarios with timestamped
  evidence and 100% reconciliation.
- **Expected files/components:** Phase 15 chaos scripts, fencing assertions,
  timeline/reconciliation extensions, Makefile failure targets, runbook drafts.
- **Architecture impact:** Empirically validates database leader election and
  failure semantics.
- **Out of scope:** Disk corruption, host loss, Kubernetes rescheduling,
  multi-region failover, or backup restore.

### P15-05 — pgBackRest Backup, WAL Archive, Restore, and PITR (COMPLETED)

- **Objective:** Establish verified recoverability independent of live database
  replicas.
- **Context:** Replication propagates operator mistakes and corruption; it is not a
  substitute for backups and PITR.
- **Dependencies:** P15-02 and the backup decisions in P15-01; may proceed in
  parallel with P15-03 after the topology is stable.
- **Scope:** pgBackRest repository, full/differential backup, continuous WAL
  archive, retention, checksums, isolated restore environment, restore point,
  PITR test, and repository-capacity controls.
- **Implementation requirements:** Pin tooling; protect repository credentials;
  prove archive continuity; create sentinel transactions around a recovery point;
  restore into isolated volumes; verify schemas, timelines, checksums, and data;
  document retention and pruning.
- **Acceptance criteria:** A verified backup restores successfully; PITR includes
  the pre-point sentinel and excludes the post-point sentinel; active cluster is
  untouched; measured restore duration meets the ADR target.
- **Verification requirements:** Backup validation, forced restore from clean
  volumes, PITR exercise, corrupted/incomplete-backup negative control, disk-bound
  checks, and independent reconciliation of restored data.
- **Expected files/components:** pgBackRest configuration, backup/restore scripts,
  dedicated repository and restore Compose profiles, Makefile targets, DR runbook.
- **Architecture impact:** Adds an independent recovery plane and WAL lifecycle.
- **Out of scope:** Cloud object storage, logical export as the primary strategy,
  cross-region backup copies, or active-cluster destructive restore.

### P15-06 — Database HA Observability, Alerts, and Runbooks (COMPLETED)

- **Objective:** Make replication, election, fencing, WAL, backup, and restore
  state actionable for operators.
- **Context:** Automated failover without role and lag visibility can hide data-risk
  conditions until recovery fails.
- **Dependencies:** P15-02, P15-04, and P15-05.
- **Scope:** Metrics collection, alert rules, dashboards/specifications, structured
  logs, primary/failover/lag/WAL/backup runbooks, and security review of management
  endpoints.
- **Implementation requirements:** Cover exactly-one-primary violation, Patroni
  leader absence, etcd quorum, sync standby absence, replication lag, slot/WAL
  growth, disk pressure, backup age/failure, archive backlog, and restore failure.
- **Acceptance criteria:** Every supported failure produces a distinct observable
  signal and links to a tested runbook; management APIs and secrets are not public.
- **Verification requirements:** Alert syntax tests, synthetic metric evaluation,
  log/metric assertions during P15-04/P15-05 scenarios, runbook walkthroughs, and
  focused security review.
- **Expected files/components:** Monitoring rules, metrics configuration,
  `docs/runbooks/postgresql-ha-failover.md`, backup/PITR runbook, architecture docs.
- **Architecture impact:** Adds operational visibility without changing database
  consistency.
- **Out of scope:** Central SaaS monitoring, on-call vendor integration, service
  mesh telemetry, or application business metrics redesign.

### P15-07 — Database HA Load, Recovery, and DR Qualification (COMPLETED)

- **Objective:** Qualify synchronous replication, primary failover, recovery,
  backup, and PITR under the approved workload.
- **Context:** Idle failover success does not expose commit latency, pool storms,
  retry amplification, WAL pressure, or outbox recovery behavior.
- **Dependencies:** P15-03 through P15-06.
- **Scope:** Same-run no-fault control, steady load, 5x spike, primary failure,
  standby failure, recovery, resource metrics, async drain, availability-window
  calculation, backup/PITR results, and reconciliation.
- **Implementation requirements:** Reuse Phase 14 APIs/workloads; record latency,
  errors, acknowledged commits, replication lag, failover timeline, pool state,
  outbox/consumer lag, WAL/repository growth, resource use, and recovery; drain
  before reconciliation.
- **Acceptance criteria:** No-fault and post-recovery p95 remain below 200ms;
  primary loss meets RTO<=30s and RPO=0 for acknowledged writes; no split brain;
  lags drain within approved bounds; restore/PITR pass; reconciliation is 100%.
- **Verification requirements:** Execute pinned black-box and failure scenarios
  from clean state, archive machine-readable results, independently verify commit
  sets and restored data, and repeat invalid runs.
- **Expected files/components:** k6/database-HA scenario, qualification runner,
  resource/metric snapshots, reconciliation extensions, evidence inputs.
- **Architecture impact:** Produces empirical database durability and recovery
  evidence for the local Phase 15 topology.
- **Out of scope:** Physical host/AZ failure, production-wide 99.9% certification,
  autoscaling, or cloud cost modeling.

### P15-08 — Evidence Dossier and Phase Review (COMPLETED)

- **Objective:** Consolidate evidence, verify architecture and security, and close
  Phase 15 only when every database reliability claim is supported.
- **Context:** Phase progression requires a passed review, not successful promotion
  alone.
- **Dependencies:** P15-01 through P15-07.
- **Scope:** Evidence dossier, architecture/current-phase updates, ADR consistency,
  runbook completion, task verification, failure analysis, security review,
  architecture review, and phase review.
- **Implementation requirements:** Link each exit criterion to commands, topology,
  versions/digests, metrics, timelines, reconciliation, backup manifests, restore
  evidence, limitations, and unresolved risks; advance phase only after review.
- **Acceptance criteria:** No unresolved correctness, fencing, durability,
  recoverability, security, architecture, or evidence gap remains; Phase 16 is
  revalidated against actual Phase 15 state before approval.
- **Verification requirements:** `make verify`, all Phase 15 smoke/failover/backup/
  restore/qualification targets, cleanup checks, complete diff review, formal
  verification and phase-review procedures.
- **Expected files/components:** `docs/bootcamp/evidence/p15-postgresql-ha.md`,
  review reports, living architecture documents, and current-phase update.
- **Architecture impact:** Certifies only the Phase 15 local database HA and DR
  boundary; does not authorize Kubernetes implementation.
- **Out of scope:** Implementing or automatically approving Phase 16.

---

## 19. Task Acceptance-Criteria Matrix

| Task | Required acceptance outcome |
|---|---|
| P15-01 | ADR-0024 accepted with explicit synchronous durability, fencing, RPO/RTO, recovery, and claim boundaries |
| P15-02 | Three PostgreSQL/Patroni nodes and three etcd members bootstrap reproducibly with one primary and healthy replication |
| P15-03 | Applications and Flyway use primary-aware connectivity and recover pools without restart |
| P15-04 | Primary loss promotes safely within RTO, RPO=0, no split brain, and old node rejoins safely |
| P15-05 | Verified backup restore and PITR succeed in isolation with bounded retention |
| P15-06 | HA/backup risks are observable, alerted, secured, and covered by tested runbooks |
| P15-07 | Load/failover/DR qualification meets SLOs and ends with 100% reconciliation |
| P15-08 | Evidence, verification, reviews, and documentation pass with remaining host/ingress limits explicit |

Runtime tasks cannot pass on configuration inspection alone.

---

## 20. Verification Requirements for Every Task

Every implementation task must:

1. Re-read `AGENTS.md`, the constitution, current phase, this plan, and its task.
2. Confirm P15-01 and declared dependencies are approved and complete.
3. Preserve existing Phase 14 and unrelated user changes.
4. Implement only task scope and avoid Phase 16 technologies.
5. Run the focused configuration, integration, security, failure, load, backup,
   restore, or reconciliation checks named by the task.
6. Run `make verify` unless documentation-only, plus the task-specific Phase 15
   target.
7. Capture environment, image digests, topology, commands, metrics, timelines,
   commit sets, backup manifests, and reconciliation evidence.
8. Inspect git status and the complete diff; reject secrets, unsafe cleanup,
   unrelated edits, and unintended generated files.
9. Use task verification and issue-recording procedures as applicable.
10. Stop after the assigned task; do not start dependents automatically.

---

## 21. Phase Exit Criteria

Phase 15 is complete only when:

1. ADR-0024 is accepted and implemented without undocumented deviation.
2. The Phase 15 topology reproducibly runs three PostgreSQL/Patroni nodes and
   three etcd members with exactly one writable primary.
3. Strict synchronous replication acknowledges business commits only after at
   least one standby confirms durability.
4. Single-primary loss recovers public writes within 30 seconds with zero loss of
   acknowledged transactions.
5. Split-brain, DCS quorum-loss, sync-standby-loss, old-primary-return, and replica
   rejoin tests exhibit only their approved safe behavior.
6. Application pools and Flyway discover the current primary without manual URL
   changes, cross-schema access, or process restart.
7. Replication slots, WAL retention, disk pressure, roles, timelines, and lag are
   bounded, observable, and covered by alerts/runbooks.
8. A pgBackRest backup restores into an isolated environment and a PITR exercise
   proves the selected recovery boundary.
9. No-fault and post-recovery critical API p95 are below 200ms under the approved
   workload, and recovery lag drains within approved deadlines.
10. Every valid failure/restore run ends with zero unexpected DLQ messages, no
    stranded committed outbox events after drain, and 100% reconciliation.
11. Security review finds no exposed DCS/Patroni/backup management interface,
    committed secret, or excessive database privilege.
12. Evidence names HAProxy, Docker host/runtime, storage host, zone, and region as
    remaining failure domains and makes no production-wide 99.9% claim.
13. `make verify`, Phase 15 qualification targets, task verification, failure
    analysis, architecture review, security review, and phase review pass.
14. Phase 16 is not approved until this completed state is inspected and its
    conditional plan is revalidated.

---

## Dependency Graph

```text
P15-01 ──> P15-02 ──+──> P15-03 ──> P15-04 ──+
                    |                           +──> P15-06 ──> P15-07 ──> P15-08
                    +──> P15-05 ───────────────+
```

Required order:

1. P15-01 first, followed by P15-02.
2. P15-03 and P15-05 may proceed in parallel after P15-02.
3. P15-04 requires primary-aware clients from P15-03.
4. P15-06 requires failover and backup behavior from P15-04/P15-05.
5. P15-07 requires all implemented and observable capabilities.
6. P15-08 is the final review gate.
