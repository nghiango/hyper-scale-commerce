# Phase 16 Conditional Plan: Kubernetes Orchestration & Multi-Node Reliability

**Phase:** Phase 16 — Kubernetes Orchestration & Multi-Node Reliability

**Status:** COMPLETED

**Date:** 2026-08-17

---

## 1. Phase Objective

Package and qualify the verified Phase 15 high-availability topology on a
reproducible multi-node Kubernetes cluster so the platform gains:

1. Declarative deployment, health-based self-healing, controlled rollout, and
   disruption protection for stateless and stateful components.
2. Logical node-failure tolerance through replica placement, anti-affinity,
   topology spread, quorum-aware disruption budgets, and resource isolation.
3. A replicated ingress tier that preserves routing, security controls, and
   topology-wide rate-limit semantics during one ingress-pod failure.
4. Safe Kubernetes packaging of the Phase 14 Kafka quorum and expected Phase 15
   PostgreSQL/Patroni/etcd topology without changing their durability contracts.
5. Reproducible load, node-loss, rollout, scaling, recovery, and reconciliation
   evidence.

Phase 16 proves orchestration behavior on a local multi-node Kubernetes test
cluster. Because the logical nodes still share one physical host, it does not
claim real multi-host, multi-zone, or production-wide 99.9% availability.

---

## 2. Why This Phase Exists

Phase 14 proved replica and Kafka failure behavior through explicit Docker
Compose topology. Phase 15 is expected to prove PostgreSQL failover, fencing,
backup, and PITR through explicit containerized components. Compose remains
valuable for understanding those mechanisms, but it does not provide a
scheduler, declarative convergence, topology-aware placement, disruption
budgets, rolling-update control, or autoscaling.

Moving to Kubernetes before database failover is verified would hide database
semantics behind orchestration abstractions. Moving directly to a cloud provider
would mix application packaging, stateful scheduling, infrastructure
provisioning, managed services, IAM, networking, and regional design in one
phase. Phase 16 therefore introduces Kubernetes locally after Phase 15 and
preserves all proven application, Kafka, and PostgreSQL contracts.

---

## 3. Starting Architecture / State

This section is provisional until P16-01 revalidates it against completed Phase
15 evidence.

- Phase 15 is expected to be approved and complete.
- `app` and `order-query` have multiple replicas and primary-aware PostgreSQL
  connectivity.
- HAProxy provides public routing, readiness checks, forwarded-header sanitation,
  admin-route protection, and topology-wide rate limiting.
- Kafka has three KRaft broker/controllers with RF=3 and min ISR=2.
- PostgreSQL is expected to have three Patroni-managed nodes, etcd quorum,
  synchronous replication, fencing, backup, WAL archive, restore, and PITR.
- All components run through Docker Compose on one host with explicit scripts
  performing startup, failure injection, and recovery.
- There are no Kubernetes resources, Helm charts, resource requests/limits,
  disruption budgets, topology spread rules, or horizontal autoscalers.
- Public ingress, cluster control plane, nodes, and volumes have not been
  qualified under Kubernetes failure behavior.

---

## 4. Target Architecture / State

```text
                     k6 / external client
                              |
                     Kubernetes Service
                              |
              HAProxy ingress StatefulSet (2 pods)
                    peer-synchronized quotas
                       /                \
              app Deployment      query Deployment
                 3+ pods              3+ pods
                       \                /
                    Kafka StatefulSet (3)
                      RF=3 / min ISR=2
                              |
          PostgreSQL/Patroni StatefulSet (3) + etcd (3)
                              |
                    pgBackRest repository PVC

        3 control-plane nodes + 3 worker nodes (local kind cluster)
        anti-affinity + topology spread + PDBs + requests/limits
```

- A version-pinned kind cluster provides three Kubernetes control-plane nodes and
  three workers for reproducible logical node qualification.
- Helm packages environment-neutral resources and validates rendered manifests.
- StatefulSets and headless Services preserve stable identities for Kafka,
  PostgreSQL/Patroni, etcd, and HAProxy peers.
- Deployments manage stateless application replicas with rolling updates,
  readiness/liveness/startup probes, topology spread, requests, limits, and HPA.
- PodDisruptionBudgets preserve Kafka, etcd, PostgreSQL, application, and ingress
  quorum/availability during voluntary disruptions.
- NetworkPolicies, RBAC, Secrets, and pod-security settings restrict control and
  data-plane access.
- Kubernetes does not replace Patroni, Kafka quorum, outbox, or idempotency
  semantics; it restarts and schedules their processes.

---

## 5. Problems This Phase Addresses

1. **Manual convergence:** Compose and scripts require explicit operator restart
   and placement decisions.
2. **No logical node isolation:** All replicas share one undifferentiated runtime
   placement domain.
3. **Unsafe voluntary disruption:** There are no disruption budgets protecting
   database or broker quorum.
4. **No declarative rollout:** Application updates are not governed by surge,
   unavailable, readiness, and rollback policies.
5. **Single ingress process:** Phase 14 HAProxy remains one public-routing process.
6. **No resource governance:** CPU/memory requests, limits, QoS, and autoscaling
   behavior are not declared or tested.
7. **Configuration drift:** Component topology is spread across Compose and shell
   scripts rather than rendered, diffable deployment packages.
8. **No scheduler failure evidence:** Pod, worker, control-plane, eviction, drain,
   and rescheduling behavior are unverified.

---

## 6. Architecture Changes

- Introduce a version-pinned multi-node kind cluster as test-only Kubernetes
  infrastructure.
- Add Helm charts for namespace, configuration, Secrets references, Services,
  workloads, storage, policies, monitoring, jobs, and test hooks.
- Deploy applications as Deployments and quorum/stateful systems as StatefulSets
  with stable identities and persistent volume claims.
- Replace the single HAProxy container with two stable HAProxy pods behind a
  Kubernetes Service and synchronize stick-table state through HAProxy peers.
- Add readiness, liveness, and startup probes with failure semantics appropriate
  to each component; liveness must not trigger destructive failover loops.
- Add anti-affinity, topology spread, priority classes where justified,
  PodDisruptionBudgets, requests/limits, and HorizontalPodAutoscalers for
  stateless services only.
- Add Kubernetes-specific safe failure and qualification tooling while retaining
  Docker Compose as a developer and mechanism-level regression environment.
- Preserve database ownership, Kafka durability, APIs, event contracts, and
  application dependency direction.

---

## 7. Technology Changes

### Introduced in Phase 16

- Kubernetes, with the exact minor version pinned by P16-01.
- kind as the local multi-node Kubernetes test environment.
- Helm 3 for packaging, rendering, configuration, and release lifecycle.
- Kubernetes Metrics Server for test-only HPA signals.
- Kubernetes NetworkPolicy, RBAC, PodDisruptionBudget, HPA, StatefulSet,
  Deployment, Job, CronJob, ConfigMap, Secret, and Service primitives.

### Retained

- Existing application images, HAProxy, Kafka KRaft, PostgreSQL 16, Patroni,
  etcd, pgBackRest, Flyway, k6, Micrometer, Prometheus rule definitions, and
  safe failure/reconciliation scripts, subject to Phase 15 validation.

### Technology rules

- All cluster, tool, and workload images are version- and digest-pinned.
- Helm charts must not embed credentials or environment-specific secrets.
- Kubernetes controllers do not redefine PostgreSQL or Kafka leader election.
- HPA applies only to stateless application workloads unless a later ADR proves a
  safe stateful scaling model.
- Compose remains available for focused local development unless the Phase 16 ADR
  explicitly retires a path with migration evidence.

---

## 8. Non-Functional Requirements

- Deleting one `app`, `order-query`, or HAProxy pod must preserve public API
  availability after bounded Service/readiness convergence.
- Loss of one worker node must preserve public APIs, Kafka quorum, PostgreSQL
  write availability, etcd quorum, and acknowledged-data durability within the
  logical kind topology.
- Rolling application updates must drop zero accepted critical requests.
- Voluntary drain must be blocked when it would violate a stateful quorum or
  minimum application availability.
- All workloads declare justified requests and limits; no critical pod may run
  BestEffort.
- Every failure and rollout scenario ends with 100% reconciliation after drain.
- Rendered manifests must be reproducible and pass schema, policy, and security
  validation before cluster application.
- Results must state that kind nodes share one physical Docker host.

---

## 9. Performance Expectations

- No-fault critical API p95 remains below 200ms under the approved Phase 15
  workload.
- Kubernetes Service plus replicated ingress overhead is measured against the
  Phase 15 same-host control; no unsupported latency budget is invented before
  P16-01 records the environment.
- Pod replacement after abrupt stateless pod loss completes within 60 seconds.
- Critical API p95 returns below 200ms within 60 seconds after worker-node loss
  reaches stable scheduling and dependency quorum.
- HPA scales stateless workloads within the approved window and returns toward
  baseline without oscillation; exact thresholds derive from measured resource
  saturation rather than arbitrary values.
- Rolling update and rollback complete within an approved bounded window while
  maintaining error and latency objectives.

---

## 10. Reliability Expectations

- Three control-plane nodes retain Kubernetes API/etcd control-plane quorum after
  one control-plane container loss.
- Application and ingress replicas are distributed across workers through hard
  or preferred anti-affinity justified by the ADR.
- Kafka, Patroni/PostgreSQL, and etcd maintain their existing quorum and
  synchronous durability contracts during one worker loss.
- PDBs prevent voluntary operations from removing enough pods to break quorum.
- Stateful pod identity and storage attachment behavior are explicit; Kubernetes
  restart must not promote, clone, or delete database state unsafely.
- Readiness removes pods before traffic; liveness detects deadlock without
  repeatedly killing a slow-but-correct dependency-recovering process.
- Failed rollouts automatically stop and have a tested rollback procedure.
- HPA and application-local/background workers do not violate outbox, consumer,
  cache, rate-limit, or idempotency invariants as replica counts change.

---

## 11. Observability Requirements

- Capture desired/available/ready replicas, restart count, scheduling failures,
  rollout status, HPA decisions, PDB state, node readiness, resource throttling,
  evictions, PVC state, and Kubernetes events.
- Preserve application correlation IDs, instance identity, Kafka metrics,
  database role/lag, backup state, ingress metrics, and reconciliation evidence.
- Alerts distinguish pod loss, worker loss, control-plane degradation, failed
  rollout, PDB blockage, resource saturation, quorum risk, volume failure, and
  application/dependency readiness.
- Failure timelines correlate Kubernetes events with HAProxy, application,
  Kafka, Patroni, etcd, PostgreSQL, and pgBackRest signals.
- Metrics labels remain bounded and do not contain user IDs, client IPs, pod UIDs
  where stable workload labels suffice, or secrets.
- Runbooks include diagnostic `kubectl` commands, expected states, rollback,
  escalation, and reconciliation gates.

---

## 12. Security Considerations

- Use dedicated namespaces and least-privilege ServiceAccounts/RBAC; application
  pods must not list Secrets or mutate cluster resources.
- Enforce restricted pod-security settings where compatible: non-root users,
  read-only root filesystem, dropped Linux capabilities, seccomp, and no
  privileged or host-path access.
- NetworkPolicies allow only required ingress, application, Kafka, PostgreSQL,
  etcd/Patroni, backup, DNS, and monitoring flows.
- Secrets are supplied at deployment time and are excluded from Helm values,
  rendered artifacts, logs, and evidence.
- HAProxy continues to sanitize forwarded identity and block administrative
  routes; peer synchronization is internal-only.
- Kubernetes API credentials and kubeconfig remain test-environment assets and
  are never committed.
- Container image provenance and vulnerability review are required before phase
  completion.

---

## 13. Data Considerations

- PostgreSQL remains the source of truth with unchanged bounded-context schema
  ownership.
- StatefulSets provide stable identity, not data durability by themselves;
  PostgreSQL streaming replication, Kafka RF/ISR, and backups remain authoritative.
- Each PostgreSQL and Kafka replica uses a distinct PVC; shared writable volumes
  across database nodes are forbidden.
- kind local volumes are tied to the physical Docker host and cannot support a
  real host-loss durability claim.
- Flyway runs only against the current writable primary using the approved Phase
  15 migration contract.
- Scaling or rolling consumers preserves event keys, consumer groups, idempotency,
  monotonic projection versions, and cache invalidation.
- Backup/PITR jobs and retention remain bounded and cannot restore destructively
  over the active cluster.

---

## 14. Explicitly Out-of-Scope Capabilities

- Terraform, cloud accounts, managed Kubernetes, managed PostgreSQL/Kafka,
  cloud load balancers, cloud object storage, or provider IAM.
- Real physical multi-host, multi-zone, regional, or active-active qualification.
- Multi-region Kafka linking/MirrorMaker, geo-replicated PostgreSQL, global DNS,
  anycast, or global rate limiting.
- Service mesh, SPIFFE/SPIRE, automatic mTLS, external secret managers, policy
  engines, GitOps controllers, or full software-supply-chain platforms.
- Database sharding, read scaling, table partitioning, new business contexts,
  new service extraction, synchronous inter-service calls, XA/2PC, or event sourcing.
- Stateful autoscaling or automatic Kafka/PostgreSQL topology resizing.
- Claiming production-wide 99.9% availability from a kind cluster on one host.

---

## 15. Dependencies on the Previous Phase

- Phase 15 must be implemented, verified, and approved before Phase 16 approval.
- P16-01 must inspect actual Phase 15 topology, ADR-0024, evidence, versions,
  connection contract, backup/restore design, and unresolved risks.
- Phase 14 Kafka, application replica, ingress security, rate-limit, and
  reconciliation invariants remain binding.
- Phase 15 PostgreSQL synchronous durability, fencing, backup, and PITR invariants
  must be preserved exactly or superseded by a new approved ADR.
- The existing load/chaos harness and runbooks provide regression controls.
- No Phase 16 task after P16-01 may start if Phase 15 review is incomplete or
  P16-01 finds a material mismatch.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Conditional plan assumes a Phase 15 design that changes | Invalid tasks or unsafe migration | P16-01 mandatory revalidation and amendment before approval |
| Kubernetes restarts are mistaken for database failover safety | Split brain or data loss | Preserve Patroni/etcd authority; test exactly-one-primary independently |
| PDBs or anti-affinity prevent all scheduling on small cluster | Qualification deadlock | Resource/capacity preflight and documented hard versus preferred placement rules |
| PDBs are bypassed by involuntary node loss | Quorum loss | Size replicas for one-node loss and test involuntary failures separately |
| Local PVCs cannot move after node loss | Stateful pod remains pending | Depend on surviving replicated state; document local-storage limits and node-restoration procedure |
| Liveness probe causes cascading restarts during dependency outage | Self-inflicted outage | Separate startup/readiness/liveness semantics and test dependency recovery |
| HAProxy peer state diverges | Rate-limit bypass after ingress failover | Stable peer identities, sync health metrics, failover quota tests, bounded fallback |
| HPA scaling causes Kafka rebalance storms or DB pool exhaustion | Latency and availability collapse | Bound min/max replicas, stabilization windows, pool-budget checks, and load tests |
| Helm values expose secrets or environment drift | Credential leak or unsafe deployment | External value injection, rendered-manifest scanning, and schema validation |
| Single-host kind result is presented as multi-host HA | Unsupported production claim | Prominent failure-domain statement and physical-host negative control prohibition |
| Full topology exceeds workstation resources | Flaky tests | Published minimum resources, preflight, smoke/full profiles, and bounded logs/artifacts |

---

## 17. ADRs That May Be Required

### Required: ADR-0025 — Kubernetes Packaging, Placement & Reliability Strategy

ADR-0025 is drafted only after Phase 15 completion and must decide:

- whether actual Phase 15 components remain valid for direct Kubernetes
  packaging or require a separately approved operator/product;
- Kubernetes and kind versions, control-plane/worker counts, resource baseline,
  and local-storage limitations;
- Helm chart boundaries, values/schema strategy, release ordering, rollback, and
  coexistence with Compose;
- workload kinds, stable identity, Services, probes, anti-affinity, topology
  spread, priorities, requests/limits, PDBs, and HPA rules;
- HAProxy replication, peer synchronization, Service exposure, quota semantics,
  and public/admin route boundaries;
- stateful bootstrap, quorum, disruption, storage, backup, restore, and migration
  behavior without weakening ADR-0023/ADR-0024;
- NetworkPolicy, RBAC, pod security, secret injection, image pinning, and evidence;
- exact availability claims and remaining physical host, storage, zone, region,
  and cloud control-plane exclusions.

If P16-01 determines that a Kubernetes operator, CSI system, ingress controller,
external secret manager, or cloud service is necessary, planning stops for a
separate ADR and explicit approval rather than silently expanding Phase 16.

---

## 18. Ordered Implementation Tasks

### P16-01 — Revalidate Phase 16 and Approve ADR-0025 (COMPLETED)

- **Objective:** Rebuild this conditional plan from actual completed Phase 15
  state and approve the Kubernetes architecture before implementation.
- **Context:** Phase 16 was drafted before Phase 15 implementation and cannot rely
  on planned database details as facts.
- **Dependencies:** Phase 15 phase review passed.
- **Scope:** Repository/topology inspection, gap analysis, plan amendment, ADR-0025,
  workload/environment contract, failure-domain matrix, migration and rollback plan.
- **Implementation requirements:** Compare actual versus assumed Phase 15 state;
  evaluate kind/Helm and alternatives; decide workload packaging, storage,
  ingress peers, probes, placement, PDBs, HPA, security, and claim boundaries.
- **Acceptance criteria:** No unresolved mismatch remains; ADR-0025 and an amended
  Phase 16 plan are approved; all later tasks remain implementable and scoped.
- **Verification requirements:** Documentation and architecture review against
  source-of-truth files, all Phase 15 evidence, runtime manifests, risks, and
  constitutional targets.
- **Expected files/components:** `docs/adr/0025-kubernetes-packaging-and-reliability.md`,
  amended `docs/bootcamp/phase-16-plan.md`, topology/failure/migration documents.
- **Architecture impact:** Authorizes or blocks Phase 16; no Kubernetes runtime is
  created by this task.
- **Out of scope:** Cluster creation, Helm charts, application changes, or
  deployment.

### P16-02 — Reproducible Multi-Node Kubernetes and Helm Foundation (COMPLETED)

- **Objective:** Provide a pinned, reproducible multi-control-plane/multi-worker
  kind cluster and validated Helm foundation.
- **Context:** Later tasks need a deterministic scheduler and packaging baseline
  before workloads are migrated.
- **Dependencies:** P16-01.
- **Scope:** kind configuration, cluster lifecycle, namespaces, storage classes,
  Metrics Server, Helm library/foundation charts, schema validation, preflight,
  and safe cleanup.
- **Implementation requirements:** Pin versions/digests; create three control-plane
  and three worker nodes; verify API quorum, DNS, storage, metrics, capacity, image
  loading, namespace/RBAC/policy baseline, and dedicated cluster naming.
- **Acceptance criteria:** Cluster starts cleanly twice; one control-plane loss
  preserves API access; all workers, DNS, metrics, storage, and policy preflights
  pass; cleanup cannot target unrelated clusters.
- **Verification requirements:** Helm lint/template/schema checks, Kubernetes
  server-side dry run, cluster smoke, control-plane loss, resource preflight, and
  interrupted-cleanup test.
- **Expected files/components:** `performance/kubernetes/kind-config.yaml`, cluster
  scripts, Helm chart foundation, policy manifests, Makefile lifecycle targets.
- **Architecture impact:** Adds test-only Kubernetes infrastructure; no business
  workload migration yet.
- **Out of scope:** Stateful systems, applications, ingress, HPA, cloud clusters.

### P16-03 — Stateful Quorum and Recovery Workloads on Kubernetes (COMPLETED)

- **Objective:** Package Kafka, PostgreSQL/Patroni, etcd, and pgBackRest on
  Kubernetes without changing their approved quorum, durability, or DR contracts.
- **Context:** Stateless migration cannot be qualified against an unverified or
  externally ambiguous dependency topology.
- **Dependencies:** P16-02 and actual Phase 15 contracts confirmed by P16-01.
- **Scope:** StatefulSets, headless/client Services, PVCs, bootstrap ordering,
  Secrets references, probes, placement, PDBs, backup Jobs/CronJobs, and focused
  quorum/recovery tests.
- **Implementation requirements:** Stable IDs; distinct PVCs; anti-affinity;
  quorum-safe PDBs; RF/ISR and sync-replication assertions; Patroni remains DB
  authority; backup/PITR remains isolated; no destructive liveness behavior.
- **Acceptance criteria:** Kafka, etcd, and PostgreSQL form healthy quorums;
  exactly one writable DB primary exists; required replication contracts hold;
  one stateful pod loss preserves service; backup verification succeeds.
- **Verification requirements:** Render/policy checks, clean bootstrap twice,
  pod-loss, quorum-state, replication, fencing, backup/restore smoke, PVC ownership,
  and Phase 14/15 invariant tests.
- **Expected files/components:** Helm stateful templates/values, probes, Services,
  PDBs, policies, test hooks, migration and recovery runbooks.
- **Architecture impact:** Moves proven stateful topology under Kubernetes process
  supervision while retaining native consensus systems.
- **Out of scope:** Stateful autoscaling, operator adoption, cloud volumes,
  multi-zone storage, or full node-loss qualification.

### P16-04 — Replicated Ingress and Stateless Application Deployments (COMPLETED)

- **Objective:** Deploy HAProxy, `app`, and `order-query` with replicated routing,
  secure configuration, controlled rollouts, and preserved API behavior.
- **Context:** Phase 14 Compose definitions do not provide Kubernetes Services,
  scheduling, rollout, or ingress-process redundancy.
- **Dependencies:** P16-02 and P16-03.
- **Scope:** Application Deployments, HAProxy StatefulSet/peer service, public and
  internal Services, ConfigMaps/Secrets references, probes, resources, placement,
  PDBs, rollout policy, admin-route protection, and smoke tests.
- **Implementation requirements:** At least three application replicas and two
  ingress peers; sanitize forwarded headers; sync quota state; keep admin endpoints
  internal; preserve primary-aware JDBC and Kafka bootstrap; enforce non-root and
  NetworkPolicy/RBAC boundaries.
- **Acceptance criteria:** Public APIs route across healthy replicas; one ingress
  or application pod loss is transparent after convergence; admin/sensitive paths
  remain blocked; quota cannot be multiplied through ingress failover.
- **Verification requirements:** Helm/policy/security checks, backend distribution,
  ingress-peer failover, forwarded-header spoofing, application pod kill, readiness
  drain, quota continuity, and existing API/integration tests.
- **Expected files/components:** Helm application/ingress templates, HAProxy peer
  config, Services, policies, probes, PDBs, rollout and ingress runbooks.
- **Architecture impact:** Replaces the Phase 16 single Compose ingress/application
  runtime with scheduler-managed replicated workloads.
- **Out of scope:** Autoscaling, node loss, full load qualification, service mesh.

### P16-05 — Resource Governance, Rolling Updates, and Stateless Autoscaling (COMPLETED)

- **Objective:** Prove bounded resources, safe rollout/rollback, PDB behavior, and
  evidence-driven HPA for stateless services.
- **Context:** Kubernetes defaults do not guarantee capacity, rollout safety, or
  stable autoscaling.
- **Dependencies:** P16-04.
- **Scope:** Requests/limits, QoS, rollout strategies, HPA, stabilization, pool and
  consumer budgets, PDB checks, rollout history/rollback, and scaling tests.
- **Implementation requirements:** Derive sizing from Phase 14/15 metrics; cap HPA
  within Kafka partition and database connection budgets; test scale out/in,
  consumer rebalances, outbox workers, caches, and rate limiting; block unsafe drain.
- **Acceptance criteria:** No critical pod is BestEffort; rollout/rollback loses no
  accepted critical request; HPA reacts and stabilizes within approved bounds;
  scaling creates no duplicate effects or pool exhaustion.
- **Verification requirements:** Resource/policy assertions, load-driven HPA test,
  scale-in/out reconciliation, rolling update, failed rollout/rollback, voluntary
  drain/PDB negative control, and latency/resource evidence.
- **Expected files/components:** Helm resources/HPA/PDB values, scaling and rollout
  scripts, capacity documentation, Makefile targets.
- **Architecture impact:** Adds scheduler-driven elasticity only to stateless tiers.
- **Out of scope:** Stateful autoscaling, vertical pod autoscaling, cluster
  autoscaler, cloud nodes, or cost optimization.

### P16-06 — Kubernetes Observability, Security, and Operations (COMPLETED)

- **Objective:** Make scheduling, rollout, security, resource, quorum, and recovery
  state observable and operationally actionable.
- **Context:** Existing application/dependency metrics do not cover Kubernetes
  workload and node lifecycle.
- **Dependencies:** P16-03 through P16-05.
- **Scope:** Kubernetes metrics/events collection, alert rules, dashboard specs,
  RBAC/NetworkPolicy/pod-security verification, image review, secrets scanning,
  and operational runbooks.
- **Implementation requirements:** Cover node/pod readiness, restart loops,
  scheduling, PDB, rollout, HPA, throttling/OOM, PVC, ingress peers, Kafka/DB/etcd
  quorum, backup, and reconciliation; preserve bounded labels and secure endpoints.
- **Acceptance criteria:** Every supported failure produces an actionable signal;
  least privilege and network isolation pass; no secret appears in repo/rendered
  evidence; runbooks are executable.
- **Verification requirements:** Alert/policy tests, RBAC impersonation checks,
  network-deny/allow tests, pod-security checks, image/security scan, secret scan,
  and runbook walkthrough during injected failures.
- **Expected files/components:** Monitoring rules, policy tests, security report,
  Kubernetes runbooks, architecture/operations documentation.
- **Architecture impact:** Adds orchestration operations and security controls
  without changing domain behavior.
- **Out of scope:** External SIEM/APM, GitOps, service mesh, external secrets
  manager, cloud IAM.

### P16-07 — Pod, Node, Rollout, and Load Qualification (COMPLETED)

- **Objective:** Qualify Kubernetes self-healing and logical node reliability under
  sustained load while preserving all application, Kafka, and database invariants.
- **Context:** Pod-level smoke tests do not prove node loss, quorum-aware placement,
  rollout behavior, or recovery under traffic.
- **Dependencies:** P16-03 through P16-06.
- **Scope:** No-fault control, 5x spike, pod loss, ingress loss, worker loss,
  control-plane loss, voluntary drain, rollout/rollback, HPA, stateful quorum
  negative controls, async drain, availability window, and reconciliation.
- **Implementation requirements:** Discover exact targets; record Kubernetes and
  component timelines; protect dedicated cluster only; distinguish involuntary
  loss from drain; measure latency/errors/recovery/resources/quorums/lag; restore
  clean state and reconcile after every valid run.
- **Acceptance criteria:** One worker loss preserves APIs and required quorums;
  pod/ingress failures and rollouts meet deadlines; p95 returns below 200ms;
  acknowledged data is not lost; final reconciliation is 100%; limitations are
  explicit.
- **Verification requirements:** Pinned black-box load and safe failure suite,
  cleanup interruption test, independent quorum and commit-set verification,
  backup-state validation, machine-readable summaries, and repeatability run.
- **Expected files/components:** Kubernetes chaos/qualification scripts, k6
  scenario, result collector, Makefile targets, evidence inputs.
- **Architecture impact:** Produces empirical orchestration reliability evidence
  for the logical multi-node single-host topology.
- **Out of scope:** Physical host destruction, real AZ failure, cloud load
  balancers, production-wide availability, or random production chaos.

### P16-08 — Evidence Dossier and Phase Review (COMPLETED)

- **Objective:** Consolidate Kubernetes evidence, review architecture/security,
  and close Phase 16 only within its demonstrated failure domains.
- **Context:** Declarative resources and passing pod tests do not establish
  production readiness without workload, quorum, security, and recovery evidence.
- **Dependencies:** P16-01 through P16-07.
- **Scope:** Evidence dossier, living architecture/current-phase updates, Helm and
  cluster documentation, migration/rollback/runbooks, verification, security,
  failure analysis, architecture review, and phase review.
- **Implementation requirements:** Link each criterion to manifests, versions,
  topology, commands, events, metrics, load results, quorum/commit evidence,
  reconciliation, security results, limitations, and rollback procedures.
- **Acceptance criteria:** No unresolved orchestration, durability, security,
  recovery, scope, or evidence gap remains; single-physical-host and local-storage
  limits are prominent.
- **Verification requirements:** `make verify`, all Phase 16 lint/render/security/
  smoke/failure/load targets, clean install/uninstall, cleanup checks, complete
  diff review, and formal review procedures.
- **Expected files/components:** `docs/bootcamp/evidence/p16-kubernetes-reliability.md`,
  review reports, architecture/current-phase docs, release/runbooks.
- **Architecture impact:** Certifies only logical multi-node Kubernetes behavior;
  it does not certify a cloud or multi-zone production deployment.
- **Out of scope:** Automatically planning or implementing cloud/multi-region work.

---

## 19. Task Acceptance-Criteria Matrix

| Task | Required acceptance outcome |
|---|---|
| P16-01 | Completed Phase 15 state is inspected; ADR-0025 and amended Phase 16 plan are approved |
| P16-02 | Pinned multi-control-plane/multi-worker kind cluster and Helm foundation reproduce safely |
| P16-03 | Kafka, PostgreSQL/Patroni, etcd, and backup workloads preserve approved quorum/durability contracts |
| P16-04 | Replicated ingress and applications route securely and survive one pod loss |
| P16-05 | Resources, PDBs, rollouts, rollback, and stateless HPA behave within capacity budgets |
| P16-06 | Orchestration risks are observable, secured, alerted, and covered by runbooks |
| P16-07 | Pod/node/rollout/load qualification passes with required quorums and 100% reconciliation |
| P16-08 | Evidence and formal reviews pass with single-host/local-storage limitations explicit |

Runtime tasks cannot pass on manifest rendering alone.

---

## 20. Verification Requirements for Every Task

Every implementation task must:

1. Re-read `AGENTS.md`, the constitution, current phase, amended Phase 16 plan,
   ADR-0025, and its task.
2. Confirm Phase 15 review and P16-01 are complete before runtime work.
3. Preserve existing Compose paths and unrelated user changes.
4. Implement only task scope and avoid cloud/multi-region technologies.
5. Run focused Helm, schema, policy, security, integration, failure, load,
   recovery, and reconciliation checks named by the task.
6. Run `make verify` plus task-specific Phase 16 targets.
7. Capture cluster/tool/image versions, rendered manifests, topology, commands,
   events, metrics, timelines, quorum/commit state, and reconciliation evidence.
8. Inspect git status and complete diff; reject secrets, unsafe cluster targeting,
   unrelated edits, and unintended generated artifacts.
9. Use task-verification and issue-recording procedures as applicable.
10. Stop after the assigned task; do not start dependents automatically.

---

## 21. Phase Exit Criteria

Phase 16 is complete only when:

1. Phase 15 passed formal review before Phase 16 approval, and P16-01 revalidated
   this plan against actual Phase 15 state.
2. ADR-0025 is accepted and implemented without undocumented deviation.
3. A pinned three-control-plane/three-worker kind cluster and Helm releases install
   reproducibly from clean state and uninstall without touching unrelated clusters.
4. Kafka, PostgreSQL/Patroni, etcd, pgBackRest, HAProxy, `app`, and `order-query`
   run with approved replica counts, stable identities, probes, resources,
   placement, PDBs, Services, and policies.
5. One control-plane loss preserves Kubernetes API quorum; one worker loss
   preserves public APIs, Kafka quorum, PostgreSQL write availability, etcd quorum,
   and acknowledged-data durability in the logical topology.
6. One ingress/application pod loss and rolling update preserve availability after
   bounded readiness/Service convergence with zero lost accepted critical requests.
7. HAProxy peer synchronization prevents normal quota multiplication or reset when
   one ingress pod fails.
8. HPA scales stateless services within Kafka partition and database connection
   budgets and stabilizes without duplicate domain effects or resource exhaustion.
9. Stateful leader-election, synchronous replication, fencing, backup, PITR,
   outbox, idempotency, ordering, and reconciliation invariants remain intact.
10. No-fault and post-recovery critical API p95 are below 200ms under the approved
    workload; 5x spike and recovery criteria pass.
11. Every valid run ends with zero unexpected DLQs, no stranded committed outbox
    rows after drain, and 100% cross-schema reconciliation.
12. Security review confirms least-privilege RBAC, effective NetworkPolicies,
    restricted pods, safe secret handling, protected admin routes, and reviewed
    images.
13. Evidence explicitly states that kind nodes and local volumes share one
    physical host and makes no real multi-host, multi-zone, or production-wide
    99.9% claim.
14. `make verify`, all Phase 16 targets, task verification, security review,
    failure analysis, architecture review, and phase review pass.

---

## Dependency Graph

```text
Phase 15 review ──> P16-01 ──> P16-02 ──> P16-03 ──> P16-04 ──> P16-05
                                                        |          |
                                                        +────┬─────+
                                                             v
                                                           P16-06
                                                             |
                                                             v
                                                           P16-07
                                                             |
                                                             v
                                                           P16-08
```

Required order:

1. Phase 15 review and P16-01 are absolute gates.
2. P16-02 creates only the cluster/packaging foundation.
3. P16-03 validates stateful dependencies before applications depend on them.
4. P16-04 deploys ingress and stateless services.
5. P16-05 adds resource governance and HPA after stable workloads exist.
6. P16-06 validates observability/security across the implemented topology.
7. P16-07 runs full failure/load qualification.
8. P16-08 is the final review gate.
