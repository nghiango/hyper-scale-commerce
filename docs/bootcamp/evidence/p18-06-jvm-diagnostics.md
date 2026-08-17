# P18-06 — JVM Diagnostics Verification

- **Date:** 2026-08-17
- **Status:** **TOOLING VERIFIED; LOAD-PROFILE EVIDENCE PENDING**

## Implemented controls

- `app` and `order-query` runtime images use a JDK 21 base so `jcmd` and JFR
  exist in the deployed container.
- Both images enable G1, native-memory summary tracking, bounded RAM
  percentages, exit-on-OOM, and heap dumps under `/diagnostics`.
- Helm mounts a pod-local `/diagnostics` `emptyDir` owned by the non-root
  application user. The volume survives a container restart but not pod
  replacement.
- `capture-jvm-diagnostics.sh` requires an explicit local PID or explicit
  pod/container and writes sensitive raw artifacts only below the ignored
  `build/phase18/jvm/` tree.
- The committed summary omits raw system-property values.

## Verification

`make jvm-diagnostics-verify` compiled and started the deterministic Java
fixture, attached with `jcmd`, and verified:

- Java-level deadlock detection;
- four intentionally parked named threads;
- retained byte-array visibility in the class histogram;
- non-empty VM, heap, thread, native-memory, and JFR artifacts; and
- successful bounded summary generation.

The drill passed on 2026-08-17. Raw output remains ignored at
`build/phase18/jvm/fixture/` because diagnostic bundles may contain sensitive
values.

Both runtime images were built as
`hyperscale-commerce-app:phase18-verify` and
`hyperscale-commerce-order-query:phase18-verify`. Container checks confirmed
UID/GID 10001, an available `jcmd`, and a writable `/diagnostics` directory.
This verifies the image contract, but not attachment to a running application
pod.

## Remaining qualification

This proves tool availability and the operator workflow, not application
performance or leak resistance. P18-01/P18-07 must still capture unprofiled and
profiled runs under equivalent Kubernetes workloads and report GC pause,
allocation, live-set, thread, CPU, and profiling-overhead measurements.
