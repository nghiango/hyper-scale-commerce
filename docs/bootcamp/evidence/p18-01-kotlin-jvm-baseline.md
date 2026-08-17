# P18-01 — Kotlin/JVM Baseline and ADR-0027

- **Date:** 2026-08-17
- **Status:** **PARTIAL — PROFILING BASELINE INVALIDATED AND MUST BE RERUN**
- **ADR:** [ADR-0027](../../adr/0027-kotlin-jvm-execution-and-concurrency-strategy.md)

## Verified baseline

- Kotlin 2.2.21 and the JDK 21 toolchain are configured centrally.
- Hand-written production Kotlin uses warnings-as-errors; generated/test code
  has the documented narrower policy.
- ADR-0027 retains bounded platform threads and blocking Spring MVC/JDBC as
  the production model. Virtual threads and coroutines are not production
  request-processing defaults.
- Production Kotlin contains no `@Value` injection and no `!!` assertion. The
  result is enforced with typed configuration tests and architecture rules.
- Five narrow production suppressions remain: two entry-point spread
  operators, two Spring bean/relay long-parameter lists, and one heterogeneous
  cache-registry cast.

Reproduce the static inventory with:

```bash
rg -n '@Value|!!' app/src/main/kotlin order-query/src/main/kotlin \
  contracts/src/main/kotlin
rg -n '@Suppress' app/src/main/kotlin order-query/src/main/kotlin \
  contracts/src/main/kotlin
./gradlew compileKotlin detekt spotlessCheck --no-daemon
```

## Concurrency inventory

| Hotspot | Owner and bound | Important invariant |
|---|---|---|
| HTTP requests | Tomcat, 200 workers / 10,000 connections | graceful lifecycle; request MDC cleared |
| JDBC | Hikari primary/replica pools, configured 30/20 defaults | no cross-thread transaction state |
| Kafka listeners | Spring Kafka containers, configured concurrency 3 | record context cleared; ordering retained |
| Outbox relay | Spring scheduler and transactional relay | claimed rows and publication remain bounded |
| Caffeine/Redis cache | caller thread and bounded L1 | single-flight load; invalidation removes stale L1/L2 result |
| Replica fencing | scheduled sampler plus atomic routing state | failures route reads to primary |
| Load shedding/rate limiting | request thread plus bounded state | counters are released in `finally` |

Detailed deterministic checks are recorded in
`p18-04-concurrency-safety.md`. Production execution-model observations and
limits are recorded in `p18-05-execution-model-qualification.md`.

## Invalidated profiling claim

The earlier version of this report claimed exact JFR overhead, GC pauses, p95,
and throughput values. The Phase 18 review found no retained raw JFR or
load-generator artifacts, and the referenced in-process workload did not meet
ADR-0014 or Phase 18 topology and duration requirements. Those numeric claims
have been removed and must not be cited.

The diagnostic fixture in `p18-06-jvm-diagnostics.md` proves capture mechanics
only. P18-01 remains incomplete until equivalent unprofiled and profiled
Kubernetes workloads are run from a clean revision and report:

- throughput and latency delta;
- CPU and allocated bytes per completed request;
- GC pause and live-set trend;
- thread count, contention, and pinned virtual-thread events where relevant;
- exact environment, dataset, duration, revision, and raw artifact locations.

## Qualification thresholds

| Dimension | Gate |
|---|---|
| Critical API latency | p95 below 200 ms |
| Catalog / Order Query reads | p95 below 10 ms / 20 ms in the comparable profile |
| Regression | no more than 10% throughput, CPU/request, or allocation/request regression |
| GC | stop-the-world total below 1% of steady window; no unexplained >200 ms pause |
| Database | no acquisition timeout; Hikari remains the backpressure boundary |
| Correctness/context | no context leak, cross-thread transaction, lost data, or duplicate business effect |

## Decision

The ADR, compiler policy, inventory, and rollback thresholds are accepted.
P18-01 is **not complete** until the valid profiling comparison is available.
