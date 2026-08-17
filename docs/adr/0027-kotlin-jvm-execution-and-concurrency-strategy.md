# ADR-0027: Kotlin/JVM Execution Model, Concurrency Invariants, and Context-Propagation Strategy

- **Status:** Accepted
- **Date:** 2026-08-17
- **Deciders:** Architecture Review Board, Antigravity AI Engineering Harness
- **Phase:** Phase 18 — Kotlin/JVM Engineering Maturity & Concurrency Safety
- **Consulted:** AGENTS.md, docs/constitution.md, docs/adr/0001-technology-stack.md, docs/adr/0009-data-access-spring-data-jdbc-jooq.md, docs/adr/0013-observability-strategy.md, docs/adr/0014-load-test-strategy.md, docs/adr/0016-production-hardening-strategy.md, docs/adr/0026-distributed-caching-and-read-replica-routing.md, Phase 18 Plan

---

## 1. Context and Problem Statement

HyperScale Commerce has evolved through Phase 17 into a multi-replica, Kubernetes-orchestrated, event-driven commerce platform with multi-level near-caching (L1 Caffeine + L2 Redis) and PostgreSQL read/write splitting.

While the distributed system architecture is proven under 5,000+ virtual users, several in-process Kotlin/JVM execution and concurrency policies remain implicit:
1. **Execution Model Selection:** The runtime relies on blocking Spring MVC (Tomcat platform threads), JDBC (blocking PostgreSQL driver), Redis Lettuce sync commands, and Spring Kafka listeners. Emerging JVM models (Java 21 Virtual Threads) and Kotlin language features (coroutines) are often proposed without verifying their interaction with thread-local transaction synchronization, Brave tracing spans, SLF4J MDC, database connection pool backpressure, and graceful shutdown.
2. **Context Propagation Guarantees:** Observability (`X-Correlation-Id`, trace IDs, span IDs) and security/transaction contexts depend on thread-local storage (`ThreadLocal`, `MDC`). Unbounded or unstructured asynchronous execution threatens context leakage, lost trace attribution, or broken transaction boundaries.
3. **Concurrency Ownership and Boundaries:** Concurrent primitives (`ConcurrentHashMap`, atomic state, Caffeine single-key loading, outbox poller threads, Kafka consumers) exist across the codebase without a centralized invariant policy defining concurrency bounds, thread ownership, and shutdown guarantees.

We need an explicit, evidence-backed strategy governing the execution model, concurrency ownership, context propagation, and runtime qualification before any concurrency changes may affect production workloads.

---

## 2. Alternatives Considered

| Option | Strengths | Weaknesses | Decision |
|---|---|---|---|
| **Option 1: Retain Platform Threads as Default + Configuration-Gated Virtual Threads Evaluation** | Deterministic thread-local transaction (`DataSourceTransactionManager`), Brave trace context, and MDC propagation; bounded Tomcat threads (200) and HikariCP pools (30 primary / 20 replica) provide predictable backpressure; zero carrier-thread pinning or scheduler surprise. | Thread-per-request memory footprint under tens of thousands of idle connections; context-switching overhead at extreme thread counts. | **ACCEPTED** (Production Default; Virtual Threads gated behind evaluation flag) |
| **Option 2: Immediate Global Virtual Threads (`spring.threads.virtual.enabled=true`)** | High request concurrency with low memory overhead per virtual thread; standard blocking imperative code. | Shifts queueing directly to HikariCP pools; potential connection acquisition starvation under 10,000+ VUs; risk of carrier-thread pinning on synchronized blocks in legacy JDBC/Kafka drivers; untested context loss under complex exception paths. | **REJECTED AS IMMEDIATE DEFAULT** (Retained for experimental qualification only) |
| **Option 3: Wholesale Kotlin Coroutine Migration (`suspend` / `Flow`)** | Fine-grained non-blocking structured concurrency in Kotlin; lightweight cooperative multitasking. | Underlying persistence (JDBC/PostgreSQL/HikariCP) and Kafka APIs are strictly blocking; running blocking JDBC on `Dispatchers.IO` requires hundreds of OS threads, yielding no I/O efficiency while breaking Spring `@Transactional` thread-bound synchronization, Brave tracing, and MDC without heavy boilerplate bridge adapters. | **REJECTED FOR PRODUCTION** (Retained strictly for test-only structured-concurrency experiments) |
| **Option 4: Full Reactive Stack Rewrite (Spring WebFlux, R2DBC, Reactive Redis/Kafka)** | End-to-end non-blocking reactive streams with minimal thread usage. | Violates ADR-0001 and ADR-0009; breaks transactional outbox pattern (`SKIP LOCKED`); replaces battle-tested PostgreSQL JDBC driver with immature R2DBC driver; represents an unapproved, high-risk architectural rewrite. | **REJECTED / FORBIDDEN** |

---

## 3. Decision Outcome

Adopt **Bounded Platform Threads as the Authoritative Production Default**, establish **Strict Concurrency Invariants**, and gate any **Java 21 Virtual Threads adoption behind empirical qualification**:

### 3.1 Production Default Execution Model
- **Request Processing:** Spring MVC on Tomcat with bounded platform thread pools (`server.tomcat.threads.max: 200`, `min-spare: 10`, `max-connections: 10000`).
- **Database Access:** Synchronous Spring Data JDBC and jOOQ using HikariCP bounded connection pools (`app.datasource.primary.maximum-pool-size: 30`, `app.datasource.replica.maximum-pool-size: 20`).
- **Distributed Caching:** Caffeine L1 in-memory lookups + Redis 7.2 L2 synchronous Lettuce commands with 1-second connect/read timeouts and strict fail-open degradation.
- **Messaging:** Spring Kafka listener containers with 3 worker threads per consumer group (`spring.kafka.listener.concurrency: 3`).
- **Background Scheduled Tasks:** Single-threaded or explicitly bounded Spring TaskSchedulers with fixed delay (`OutboxRelay`, `RoutingDataSource` lag checker, pruning services).

### 3.2 Concurrency Ownership and Backpressure Invariants
1. **Database Connection Pools as Backpressure Boundary:** HikariCP pool bounds (30 primary, 20 replica per pod) are the definitive concurrency backpressure barrier against PostgreSQL saturation. No request or worker thread pool may bypass these limits.
2. **Deterministic State Synchronization:**
   - Multi-pod and multi-thread cache eviction uses `ConcurrentHashMap` and thread-safe Caffeine `get(key, loader)` single-flight computation.
   - Replica health and replication lag state transitions use `AtomicBoolean` and `AtomicLong` ensuring lock-free, race-free visibility between poller and request threads.
   - Idempotency key reservation uses PostgreSQL `UNIQUE` constraints and atomic `INSERT ON CONFLICT DO NOTHING`.
   - Outbox batch claiming uses PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED` ensuring non-overlapping parallel worker execution.

### 3.3 Context Propagation and Observability Rules
- **Thread-Local Context Safety:** All HTTP filters (`CorrelationIdFilter`), Kafka interceptors (`CorrelationIdRecordInterceptor`), and scheduled relays (`OutboxRelay`) MUST set MDC and trace context upon entry and MUST clear/remove all entries in a guaranteed `finally` block or lifecycle callback.
- **Trace Context Continuity:** HTTP `X-Correlation-Id`, trace ID, span ID, and Kafka `correlation-id` headers MUST propagate across service boundaries and asynchronous messaging boundaries.
- **Baggage and Identifiers:** Trace attributes, thread names, and MDC values MUST NEVER contain PII, credentials, or unbounded payload data.

### 3.4 Java 21 Virtual Threads Evaluation Policy
- Virtual threads may be enabled ONLY via the explicit configuration property `spring.threads.virtual.enabled=true`.
- Virtual threads CANNOT become the production default until Task P18-05 and Task P18-07 prove:
  1. Zero carrier-thread pinning or lock contention during peak 10,000-VU workloads;
  2. Zero connection acquisition timeouts or starvation in HikariCP;
  3. 100% preservation of Spring transaction semantics, MDC context, and Brave trace spans;
  4. Graceful shutdown within the 30-second lifecycle timeout.

### 3.5 Kotlin Coroutines Boundary Policy
- `kotlinx-coroutines-core` and `kotlinx-coroutines-test` are strictly **test-scoped dependencies** for structured-concurrency, cancellation, and race experiments.
- Production coroutines are FORBIDDEN in the transactional data path (Order, Inventory, Catalog persistence).
- Any future production coroutine adoption requires:
  1. A concrete non-blocking, non-transactional use case;
  2. Bounded `CoroutineScope` owned by a Spring lifecycle bean (no `GlobalScope`);
  3. Deterministic context propagation (`MDCContext()`, `asContextElement()`);
  4. An explicit amendment to this ADR.

### 3.6 Forbidden Concurrency Patterns
The following patterns are strictly forbidden in production code:
- `GlobalScope` or unowned coroutine launches.
- Unbounded thread executors or thread creation per incoming request (`new Thread()`).
- Production usage of `runBlocking` on reactive or asynchronous bridges.
- Synchronized locks wrapping blocking I/O (to prevent carrier-thread pinning).
- Crossing thread boundaries inside an active `@Transactional` boundary without verified transaction manager synchronization.
- Swallowing `InterruptedException` without restoring thread interrupt status.

---

## 4. Consequences, Failure Modes, and Rollback Conditions

### Positive Consequences
- Guarantees predictable resource utilization and prevents database connection pool exhaustion under traffic surges.
- Ensures zero context leaks or corrupted transaction states across HTTP, Kafka, and background tasks.
- Establishes reproducible, evidence-based qualification criteria before new JVM runtime features are activated.

### Negative Consequences / Tradeoffs
- Request concurrency is bounded by Tomcat worker thread pool size (200 threads per pod).
- Evaluating virtual threads requires maintaining dual qualification test suites (platform threads vs. virtual threads).

### Failure Modes and Mitigations
| Failure Mode | Impact | Mitigation |
|---|---|---|
| **HikariCP Pool Starvation** | Connection acquisition timeout under burst concurrency | Keep Tomcat threads bounded; configure `leak-detection-threshold: 5000ms`; alert on pool wait duration. |
| **MDC / Trace Context Leak** | Stale trace IDs appearing in subsequent requests on reused worker threads | Strict `try ... finally { MDC.remove(...) }` blocks in all filters, listeners, and relays. |
| **Carrier Thread Pinning (under Virtual Threads)** | Virtual threads pinned to carrier threads during `synchronized` blocks | JFR event monitoring (`jdk.VirtualThreadPinned`); replace legacy synchronized blocks with `ReentrantLock` if virtual threads are enabled. |
| **Outbox Relay Lock Contention** | Multiple workers attempting to process the same outbox rows | `SELECT ... FOR UPDATE SKIP LOCKED` guarantees non-blocking, non-overlapping batch claims. |

### Rollback Conditions
- If virtual thread qualification reveals carrier-thread pinning, increased GC pauses, or connection acquisition timeouts:
  - Immediately revert `spring.threads.virtual.enabled` to `false`.
  - Retain the baseline platform-thread configuration with zero code changes required.

---

## 5. Explicit Non-Claims

- **Single-Physical-Host Boundary:** Evaluated within the local multi-node `kind` Kubernetes cluster on single physical host hardware.
- **No Cloud-Managed Profilers:** Uses standard OpenJDK 21 diagnostic tools (Java Flight Recorder, `jcmd`, GC logs) without third-party APM agents.
- **Verification Requirement:** Acceptance of this ADR authorizes the execution and concurrency rules; it does not certify that virtual threads are accepted for production until Phase 18 qualification evidence is verified.
