# P18-04 — Deterministic Concurrency and Context-Safety Evidence

- **Date:** 2026-08-17
- **Status:** **FUNCTIONAL GATES PASSED; DISTRIBUTED FAILURE GATES PENDING P18-07**
- **Decision:** bounded platform threads remain the production model.

## Verified invariants

| Invariant | Reproducible check | Result |
|---|---|---|
| MDC and Brave context do not contaminate reused workers | `ContextSafetyConcurrencyTest` runs 64 tasks on 16 platform threads and checks cleanup | Passed |
| Database concurrency is bounded | `DataSourceBackpressureConcurrencyTest` drives 10 contenders through a 3-connection Hikari pool | Passed; active connections never exceeded 3 and excess demand timed out |
| Cache invalidation cannot leave a completed in-flight stale load in L1 or L2 | Both services' `NearCacheTest` coordinate load and eviction with latches | Passed |
| Production code does not create unowned/unbounded execution models | `KotlinEngineeringPolicyArchitectureTest` in both services | Passed for `GlobalScope`, cached thread pools, virtual-thread-per-task executors, and `@Value` |
| Typed instance identity is bindable | `AppPropertiesTest` | Passed with `app.instance-id=pod-a`; deployment defaults use `${HOSTNAME:local}` |

The cache race test found a real stale-resurrection path during remediation:
an in-flight Caffeine load could write an obsolete value to shared L2 while a
Kafka invalidation consumer waited to invalidate L1. Event-driven eviction now
repeats the idempotent L2 deletion after the local invalidation completes in
both services. The caller already inside the stale load may receive its
snapshot, but subsequent reads cannot retrieve that value from either cache.

The complete build also exposed a cross-topic event-ordering race. An
`OrderCancelled` version 2 event could reach the query service before its
`OrderPlaced` version 1 event and be discarded because no read model existed.
The cancellation projection now creates a versioned tombstone; a later older
placement enriches its immutable order details without regressing the
`CANCELLED` status or version. A focused integration test covers this order,
and the end-to-end saga compensation test passes.

## Ownership and bounds

| Work | Owner | Bound |
|---|---|---|
| HTTP requests | Spring Boot/Tomcat lifecycle | 200 workers, 10,000 connections |
| Primary and replica JDBC | Spring/Hikari lifecycle | configured maximum pool sizes (30/20 defaults) |
| Kafka listeners | Spring Kafka container lifecycle | configured listener concurrency (3 defaults) |
| Outbox polling | Spring scheduler lifecycle | one scheduled relay invocation per bean |
| Request/Kafka diagnostic context | filter/interceptor scope | cleanup in `finally`/record completion |

## Limits of this evidence

These deterministic tests do not prove graceful termination under a live
rolling deployment, multi-pod Kafka ordering, 10,000-user latency, or behavior
during Redis/PostgreSQL/Kafka failure. Those are fail-closed P18-07 gates and
remain pending until the Kubernetes qualification produces raw evidence and
reconciliation output.
