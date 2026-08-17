# P18-05 — Execution-Model Evaluation Record

## 1. Executive Summary and Qualification Decision

This report documents the bounded functional evaluation of candidate concurrency execution models for HyperScale Commerce, comparing:
1. **Bounded Platform Threads (Production Default):** Tomcat platform worker pool (`max: 200`, `min-spare: 10`) + HikariCP connection pools (`primary: 30`, `replica: 20`).
2. **Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`):** Loom-based unpinned lightweight thread-per-task model.
3. **Kotlin Structured Coroutines (`kotlinx-coroutines-core`):** Suspended cooperative concurrency with structured scopes.

### Authoritative Qualification Decision
**RETAIN BOUNDED PLATFORM THREADS AS THE PRODUCTION DEFAULT (ADR-0027).**
- Java 21 Virtual Threads and Kotlin Coroutines remain **DEFERRED** for production request serving and transactional processing.
- `kotlinx-coroutines-core` and `kotlinx-coroutines-test` remain restricted to **test-scoped experiments**.

---

## 2. Functional Comparison Matrix

| Evaluation Dimension | Bounded Platform Threads (Current) | Java 21 Virtual Threads (Loom) | Kotlin Structured Coroutines | Qualification Finding |
| :--- | :--- | :--- | :--- | :--- |
| **Throughput under Concurrency** | Not qualified by P18-05 | Not qualified by P18-05 | Not qualified by P18-05 | Performance conclusions are deferred to valid P18-07 evidence. |
| **p95 Latency** | Not qualified by P18-05 | Not qualified by P18-05 | Not qualified by P18-05 | Synthetic sleeps are not API latency evidence. |
| **Backpressure Ceiling** | HikariCP pool limit (30/20) + Tomcat worker cap (200) | Unbounded virtual thread creation risks overwhelming DB queue | Requires explicit Channel/Semaphore bounding | Platform threads provide native multi-layer backpressure barriers. |
| **Carrier Thread Pinning Risk** | N/A (runs directly on OS thread) | Pinning risk on native `synchronized` blocks in older JDBC/crypto drivers | None (suspension-based) | Platform threads eliminate carrier thread starvation risks. |
| **Context Propagation Fidelity** | Standard `ThreadLocal`, Brave `TraceContext`, SLF4J MDC | Observed semantics differ: Brave scope inherited, MDC did not; explicit policy is required | Requires explicit `CoroutineContext` elements | Keep one established request/listener model rather than mixing implicit propagation rules. |
| **Transaction Synchronization** | Fully synchronous `@Transactional` Spring / JDBC boundary | Focused test shows the parent Spring transaction is not inherited by a child virtual thread | Suspension/dispatcher changes require Spring integration proof | Transaction-bound work must remain on its owning production thread. |
| **Production Decision** | **APPROVED PRODUCTION DEFAULT** | **DEFERRED** | **DEFERRED (TEST-ONLY)** | Complies with ADR-0027. |

---

## 3. Detailed Technical Analysis

### 3.1 PostgreSQL JDBC and HikariCP Backpressure
The critical throughput ceiling of HyperScale Commerce is determined by PostgreSQL connection pool concurrency (HikariCP bounded at 30 connections for primary, 20 for replica). Spawning thousands of virtual threads does not increase PostgreSQL transaction throughput; instead, it causes excess threads to queue in HikariCP, increasing latency variance without increasing database IOPS.

### 3.2 Context Propagation & MDC Isolation
Tests executed in `VirtualThreadsQualificationTest` and
`ContextSafetyConcurrencyTest` verify bounded task-local cleanup. A separate
test observes that Brave's current span is inherited by the virtual thread
while MDC is not, so adopting virtual threads would require an explicit,
tested propagation policy. They do not by themselves prove HTTP, Kafka, or
production-scale behavior.
Those claims require the focused integration and P18-07 gates.

### 3.3 Kotlin Coroutines in Test Scenarios
`KotlinCoroutinesEvaluationTest` deterministically verifies that a failing
child cancels its sibling within the parent test scope, without adding
coroutines to production code.

---

## 4. Conclusion

In accordance with **ADR-0027**, HyperScale Commerce maintains its previously
verified bounded platform-thread model. This task does not claim that Phase 18
performance, failure, or soak qualification has passed.
