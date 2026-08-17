# Phase 18 — Evidence Dossier

- **Date:** 2026-08-17
- **Phase status:** **IN PROGRESS — NOT QUALIFIED**
- **Latest verified architecture baseline:** Phase 17

The initial Phase 18 review failed because committed reports claimed
production-scale results that were produced by a short, single-process smoke
test. Those results are preserved as invalidated history in
`evidence/p18-07-load-spike-soak.md`; they are not accepted evidence.

## Current gate status

| Task | Status | Evidence / remaining gate |
|---|---|---|
| P18-01 Kotlin/JVM baseline | Partial | ADR and compiler baseline exist; measured unprofiled/JFR baseline must be rerun and raw artifacts retained locally |
| P18-02 shared compiler/static analysis | Verified locally | Root compiler policy, Detekt, formatting, and architecture rules |
| P18-03 typed configuration | Completed | No production `@Value` or `!!`; typed property tests, hostname-backed instance identity, compatibility tests, and complete build pass |
| P18-04 concurrency safety | Functional gates verified | `evidence/p18-04-concurrency-safety.md`; live shutdown/failure behavior remains part of P18-07 |
| P18-05 execution-model decision | Functional gates verified | `evidence/p18-05-execution-model-qualification.md`; bounded platform threads retained |
| P18-06 JVM diagnostics | Tooling verified locally | `evidence/p18-06-jvm-diagnostics.md` and operator runbook; workload profiling remains pending |
| P18-07 qualification | **Not qualified** | Must run the fail-closed Kubernetes harness on a clean revision |
| P18-08 phase review | **Not complete** | Cannot pass before P18-01 and P18-07 evidence gates |

## Reproduction commands completed during remediation

```bash
./gradlew spotlessApply :app:test :order-query:test \
  --tests '*NearCacheTest' \
  --tests '*VirtualThreadsQualificationTest' \
  --tests '*KotlinCoroutinesEvaluationTest' \
  --tests '*KotlinEngineeringPolicyArchitectureTest' \
  --tests '*AppPropertiesTest' --no-daemon

make jvm-diagnostics-verify
make k8s-helm-lint k8s-stateful-verify k8s-stateless-verify \
  k8s-hpa-verify k8s-security-verify
make k8s-redis-verify
```

All commands above passed on 2026-08-17. The `jcmd` fixture and Docker runtime
checks require host permissions unavailable inside the restricted sandbox and
were rerun with explicit approval.

The final remediation gate also passed:

```bash
./gradlew build --no-daemon -q
```

## Formal qualification contract

Run this only from a clean worktree representing the revision under test:

```bash
make phase18-qualification
```

`performance/kubernetes/scripts/run-phase18-qualification.sh` checks the
cluster, requires deployments annotated for the exact clean revision, records
their immutable image IDs, sends all k6 traffic through public
ingress, executes three 10,000-VU steady runs, the 5x spike, a 30-minute soak,
and isolated Redis, replica, Kafka, and application-pod failures. It captures
resource samples and bounded JFR bundles, then requires SQL reconciliation and
an empty unexpected DLQ count. Dirty-worktree runs are explicitly marked as
rehearsals and cannot qualify the phase.

## Exit decision

Phase 18 remains **NOT QUALIFIED**. A formal phase review may be requested only
after the missing P18-01 profiling comparison and P18-07 raw qualification
artifacts pass their thresholds.
