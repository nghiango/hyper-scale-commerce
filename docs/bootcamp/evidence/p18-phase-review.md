# Phase 18 Review — Remediation Record

- **Review date:** 2026-08-17
- **Decision:** **FAILED — REMEDIATION IN PROGRESS**
- **Latest verified baseline:** Phase 17

## Why the review failed

1. P18-07 claimed a 10,000-user, 5x-spike, soak, and failure qualification,
   but the implementation was a short single-process integration smoke test.
2. Exact JFR overhead, GC, latency, throughput, and heap conclusions had no
   retained raw evidence.
3. JVM runtime images did not contain the required diagnostic tools.
4. Configuration migration lost pod identity by falling back to `local`.
5. Concurrency coverage did not exercise invalidation/load races, structured
   cancellation, virtual-thread transaction/context behavior, or forbidden
   production execution models.
6. Governance documents declared all Phase 18 tasks complete before a formal
   review passed.

## Corrections completed

- Invalid performance claims are explicitly retained as **INVALIDATED** audit
  history and the phase/dossier no longer claim completion.
- A fail-closed Kubernetes qualification harness now enforces a clean
  revision, deployed revision annotation, minimum durations, three 10,000-VU
  runs, exact 5x spike, final recovery window, soak, isolated faults, dropped
  iteration checks, resource/JVM artifacts, unexpected DLQ delta, and SQL
  reconciliation.
- JDK-based non-root images and explicit local/pod diagnostic scripts were
  added; the controlled deadlock/thread/heap/JFR fixture passes locally.
- `${HOSTNAME:local}` is bound through validated typed configuration.
- Deterministic tests cover cache invalidation racing an in-flight load,
  Hikari bounds, platform-thread cleanup, virtual-thread context/transaction
  semantics, coroutine sibling cancellation, and forbidden production APIs.
- The full build exposed a cross-topic ordering defect: cancellation version 2
  could arrive before placement version 1 and be discarded. The query model
  now creates a cancellation-first tombstone, enriches it when the older
  placement arrives, and never regresses status/version. Focused projection
  and saga tests pass.
- The complete repository build passes after the remediation diff. Docker
  runtime images were also built and verified as UID/GID 10001 with `jcmd`
  present and `/diagnostics` writable.

## Remaining blockers to a passing review

- Reproduce the P18-01 unprofiled/JFR comparison with raw artifacts.
- Repair or recreate the local kind cluster, then deploy immutable images for
  the clean candidate revision with the Helm revision annotation and verify
  in-pod diagnostic capture. The existing cluster API is unavailable because
  Docker reassigned control-plane IPs while the etcd peer certificates retain
  the previous IP SANs. Recreating it is intentionally not automated because
  it destroys cluster-local data.
- Execute and analyze the complete P18-07 qualification, including JFR
  allocation/GC/live-set/CPU review and generator saturation evidence.
- Run the independent Phase 18 review and change this decision only if every
  exit criterion is evidenced.

No Phase 18 performance, resilience, or completion claim is approved by this
record.
