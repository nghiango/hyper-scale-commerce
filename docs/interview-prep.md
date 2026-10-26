# HyperScale Commerce — Senior Backend Interview Prep

Answers are written at the depth expected from a **senior / staff backend engineer**:
first a short headline answer, then the mechanics, trade-offs, failure modes, and how
the idea shows up in *this* repository. Repo-specific facts were taken from the `java`
branch source (class names, config values, metric names). Use them to ground answers.
Interviewers trust "here is what we did and why" more than textbook definitions.

> **How to answer at senior level:** (1) give the one-sentence answer, (2) explain the
> mechanism, (3) name the trade-off / failure mode, (4) say how you applied it and
> what you measured, (5) say what you would change next. Each answer below follows that shape.

---

## Table of Contents
1. [Project Introduction](#1-project-introduction)
2. [Core Java](#2-core-java--qa)
3. [Concurrency & the JVM](#3-concurrency--the-jvm--qa)
4. [Spring / Spring Boot](#4-spring--spring-boot--qa)
5. [Caching](#5-caching--qa)
6. [Kafka & Event-Driven Architecture](#6-kafka--event-driven-architecture--qa)
7. [Distributed Systems Patterns](#7-distributed-systems-patterns--qa)
8. [Scaling, Resilience & Kubernetes](#8-scaling-resilience--kubernetes--qa)
9. [Monitoring & Observability](#9-monitoring--observability--qa)
10. [Database (PostgreSQL)](#10-database-postgresql--qa)
11. [Testing Strategy](#11-testing-strategy--qa)
12. [Security](#12-security--qa)
13. [System Design Scenarios](#13-system-design-scenarios)
14. [Known Gaps & Trade-offs in This Repo](#14-known-gaps--trade-offs-in-this-repo-be-ready-to-defend-or-improve)
15. [Likely Deep-Dive / Follow-up Questions](#15-likely-deep-dive--follow-up-questions)
16. [Behavioral Questions Anchored in This Project](#16-behavioral-questions-anchored-in-this-project)
17. [Hands-on Java Coding Q&A](#17-hands-on-java-coding-qa)
18. [Hands-on Spring, Kafka & SQL Q&A](#18-hands-on-spring-kafka--sql-qa)
19. [Production Troubleshooting Playbook](#19-production-troubleshooting-playbook--how-do-you-narrow-down-and-identify-the-problem)

---

## 1. Project Introduction

**HyperScale Commerce** is a commerce platform that evolves incrementally from a
modular Spring Boot monolith into a resilient, cloud-native distributed system.
A phased "BootCamp" program drives the evolution. Each phase has a plan, ADRs, and
evidence (`docs/bootcamp/`, `docs/adr/0001…0027`). Targets in `AGENTS.md`: **10,000+
concurrent users, p95 < 200 ms on critical APIs, survive 5× traffic spikes, 99.9%
availability, zero intentional data loss**.

> Note: `README.md` still says "Phase 5 — Service Extraction". The phase docs and
> ADRs go up to **Phase 18 (JVM engineering maturity & concurrency safety)**, and the
> codebase includes everything through that phase (HA Postgres, Kubernetes, near-caching,
> read replicas, load shedding).

### 30-second pitch (memorize)
"It's an order-management commerce backend I evolved from a modular monolith into an
event-driven system. Writes go through a Spring Boot monolith. It uses a
transactional outbox to publish domain events to Kafka. A separate CQRS read service
projects those events into a read model. On top of that I added a choreographed
saga with compensation, API idempotency, a two-tier Caffeine + Redis cache with Kafka-broadcast
invalidation, read/write splitting with replica-lag-aware routing, Patroni-managed
HA Postgres, and Kubernetes packaging with HPA, PDBs and probes. Every phase was
validated with k6 load tests and chaos experiments, and I kept an ADR for each
significant decision."

### Tech stack
- **Language / Runtime:** Java 21 (Gradle toolchain), Spring Boot 4.0 / Spring Framework 7, Jackson 3, Gradle multi-project build (`app`, `order-query`, `contracts`)
- **Persistence:** PostgreSQL 16 (HA via Patroni + etcd, pgBackRest), Spring Data JDBC + jOOQ (codegen from Flyway DDL), Flyway migrations, HikariCP
- **Messaging:** Apache Kafka (3 brokers, RF=3, `min.insync.replicas=2`), spring-kafka
- **Caching:** Caffeine (L1 near cache) + Redis 7.2 (L2 distributed cache, Lettuce)
- **Infra:** Kubernetes (kind, multi-node) + Helm, HAProxy 2.9 ingress with peer-synced stick tables
- **Observability:** Actuator + Micrometer/Prometheus, Micrometer Tracing (Brave), Logback JSON (LogstashEncoder), Grafana dashboard, Prometheus alert rules, runbooks
- **Quality:** JUnit 5, ArchUnit, Testcontainers (Postgres, Kafka, Redis), k6 load tests, Toxiproxy chaos, JFR/`jcmd` diagnostics

### High-level architecture
```
            ┌──────────── HAProxy (rate limit, /admin & sensitive actuator blocked) ────────────┐
            │                                                                                   │
     :8080  ▼                                                                            :8081  ▼
  ┌──────────────────────── app (modular monolith) ───────────┐        ┌──────── order-query (CQRS read side) ───────┐
  │ catalog (reads, cached)   order (commands)   inventory    │        │ OrderPlacedProjection / OrderCancelledProj. │
  │        │                      │  same TX                  │        │        │ version-guarded upsert             │
  │        │                      ▼                           │        │        ▼                                    │
  │        │            "order".orders + outbox_events ───────┼─relay─►│ Kafka ─► order_query.order_read_model       │
  │        ▼                                                  │        │ GET /orders, /orders/{id} (L1+L2 cached)    │
  │  Caffeine L1 ─► Redis L2 ─► Postgres (primary/replica)    │        │ DLQ admin replay                            │
  └───────────────────────────────────────────────────────────┘        └─────────────────────────────────────────────┘
```
- `app/` — modular monolith with bounded contexts `catalog`, `order`, `inventory`,
  `shared/outbox`. Each context owns its own Postgres schema (`catalog`, `"order"`,
  `inventory`), and ArchUnit tests enforce the boundaries. Port **8080**.
- `order-query/` — dedicated read-model service with its own schema (`order_query`)
  and its own Flyway location. It consumes `order-placed` / `order-cancelled` and serves
  `GET /orders*`. Port **8081**.
- `contracts/` — shared event records (`OrderPlacedEvent`, `OrderCancelledEvent`,
  `InventoryReservationFailedEvent`, `CacheInvalidationEvent`), versioned and additive-only (ADR-0019).
- `performance/` — k6 scripts, Helm chart, monitoring rules/dashboards, chaos and JVM diagnostics tooling.

### Key distributed-systems patterns implemented
| Pattern | Where | Why |
|---|---|---|
| Transactional Outbox | `OrderService` + `OutboxRelay` + `JooqOutboxRepository` | Atomic "DB change + event" without 2PC |
| CQRS | `app` writes, `order-query` reads | Independent scaling and read-optimized schema |
| Choreographed saga + compensation | `OrderPlacedConsumer` → `OrderCompensationConsumer` | Cross-context consistency without distributed TX |
| API idempotency | `IdempotencyService`, `"order".idempotency_keys` | Safe client retries of `POST /orders` |
| Idempotent consumers | `UNIQUE(event_id, sku)`, version-guarded upserts | At-least-once delivery → exactly-once *effect* |
| Two-tier cache | `NearCache` (Caffeine + Redis), Kafka invalidation fan-out | Sub-ms reads, DB offload |
| Read/write splitting | `TransactionRoutingDataSource`, `ReplicaLagMonitor` | Offload reads, bounded staleness |
| Load shedding & rate limiting | `AdaptiveLoadShedderFilter`, `ClientRateLimitFilter`, HAProxy | Protect critical paths under overload |
| DLQ + replay | `DeadLetterPublishingRecoverer`, `DlqReplayService` | Poison-message isolation and recovery |
| HA database | Patroni + etcd + synchronous standby | RPO≈0 failover |

### Evidence numbers you can quote
- Phase 12 cached throughput (`make load-cache`): **31,401 requests at 1,043.3 RPS sustained,
  0.00% errors**, `GET /catalog/products/{id}` **p95 1.91 ms (median 0.45 ms)** (`docs/bootcamp/evidence/p12-caching-and-lifecycle.md`).
- ADR-0027 states that the distributed architecture has been proven under **5,000+ virtual users**. The
  10k-VU qualification (`performance/k6/qualification-10k.js`) is the Phase 18 target.

---

## 2. Core Java — Q&A

**Q: What are the four OOP pillars, and how do they show up in real code?**
A: *Encapsulation*: keep invariants inside the type and hide state behind a narrow API.
*Inheritance*: an "is-a" relationship that reuses behavior. *Polymorphism*: callers depend on
an abstraction, and the runtime type picks the implementation (dynamic dispatch).
*Abstraction*: expose *what*, hide *how*.
In this repo:
- Encapsulation: domain value objects like `Money`, `Sku`, `ProductId` validate on construction, so an invalid SKU cannot exist.
- Polymorphism and abstraction: `L2CacheStore` has two implementations, `RedisL2CacheStore` and `InMemoryL2CacheStore`. `OutboxRepository` has `JooqOutboxRepository`. The application layer depends on the interfaces, so tests swap implementations.
- Senior nuance: favor *encapsulation of invariants* over getters and setters. An anemic
  model with public setters technically satisfies "encapsulation" but still lets
  invariants break.

**Q: Inheritance vs composition — when do you prefer composition?**
A: Inheritance couples the subclass to the parent's *implementation*, not just its
contract. That is the fragile base class problem: a parent change can silently break
subclasses, and you can't change the parent at runtime. Composition ("has-a") delegates to
collaborators behind interfaces, so you can swap them at runtime, test them in isolation,
and combine behaviors without class explosion.
- Use inheritance when there is a real, stable "is-a" relationship *and* you control both
  classes (e.g. a template method in a framework base class like `OncePerRequestFilter`, which
  all our filters extend).
- Otherwise use composition. `NearCache` *has* a Caffeine `Cache` and an `L2CacheStore`.
  It doesn't extend either. Swapping Redis for an in-memory store is a constructor argument.
- Rule of thumb from *Effective Java*: "favor composition over inheritance; design and document for inheritance or else prohibit it" (make classes `final`).

**Q: Abstract class vs interface? When would you use each in modern Java?**
A: An abstract class can hold state (fields), constructors, and protected helpers. It models a
partial implementation, and a class can extend only one. An interface defines a capability.
Since Java 8 it can have `default` and `static` methods, and since Java 9 `private` methods, but it
has no instance state or constructors, and a class can implement many.
- Use an interface for contracts between modules (`OrderRepository`, `L2CacheStore`).
- Use an abstract class when subclasses share state or a fixed algorithm skeleton (template method).
- Modern twist: **sealed interfaces** (Java 17) let you model closed hierarchies. In this repo,
  `IdempotencyDecision` is `sealed ... permits Proceed, Replay`. The controller does
  `if (decision instanceof IdempotencyDecision.Replay replay)`. With Java 21 pattern
  matching for `switch`, the compiler can check exhaustiveness. That is algebraic data types in Java.

**Q: What is a functional interface? Name the core types and explain method references.**
A: An interface with exactly one abstract method (SAM), optionally annotated with
`@FunctionalInterface`. Lambdas and method references target it.
- `Predicate<T>` T→boolean, `Function<T,R>` T→R, `Consumer<T>` T→void, `Supplier<T>` ()→T,
  `UnaryOperator<T>`, `BinaryOperator<T>`, `BiFunction<T,U,R>`, plus primitive specializations
  (`IntPredicate`, `ToLongFunction`…) that avoid boxing.
- Method references: static (`Integer::parseInt`), bound instance (`this::toDto`),
  unbound instance (`String::length`), constructor (`ArrayList::new`).
- In this repo: `NearCache.get(K key, Supplier<V> loader)` takes a `Supplier`, which is the
  DB loader. It runs only on a miss, so the cache stays decoupled from persistence.
- Pitfall: lambdas capture *effectively final* variables. A lambda that captures `this` keeps the
  enclosing object alive, which can cause a memory leak in long-lived callbacks.

**Q: Stream API — intermediate vs terminal operations, laziness, and when *not* to use streams?**
A: Intermediate ops (`map`, `filter`, `flatMap`, `sorted`, `distinct`) are lazy and return a
new stream. Nothing runs until a terminal op (`collect`, `forEach`, `reduce`,
`findFirst`, `anyMatch`). Streams fuse operations into one pass, and short-circuiting ops
(`findFirst`, `limit`, `anyMatch`) stop early. `sorted`/`distinct` are *stateful* and must
buffer elements.
- Don't use streams for side-effect-heavy logic, checked exceptions, or hot loops where
  allocation matters. A plain loop is often clearer and faster.
- **Parallel streams** use the shared `ForkJoinPool.commonPool()`. Never use them for blocking
  I/O (DB, HTTP) in a server: you starve every other user of the common pool.
- Example in the repo: `CacheInvalidationService.evictLocalKey` streams over
  `asMap().keySet()` with `filter(...).findFirst()`. That is O(n) over a 10k-entry cache for every
  invalidation. A senior review comment would be to convert the key directly instead of scanning.

**Q: Explain `final`, `volatile`, `synchronized`, `transient`, `native`, `static`.**
A:
- `final`: a field can't be reassigned (the object itself may still be mutable), a method can't be
  overridden, a class can't be subclassed. `final` fields also get a JMM guarantee: after
  the constructor finishes, other threads see their initialized values without
  synchronization (safe publication of immutable objects).
- `volatile`: reads and writes go to main memory and establish *happens-before*, which gives visibility
  and prevents reordering. It is **not** atomic for compound actions (`count++` is still a race).
- `synchronized`: mutual exclusion plus visibility, via an intrinsic monitor. It is reentrant.
- `transient`: Java serialization skips the field (it comes back as its default value).
- `native`: the method is implemented in native code via JNI/FFM.
- `static`: belongs to the class, not the instance. Static mutable state is shared across all threads and is
  a common source of race conditions and leaks.

**Q: Race condition vs visibility bug vs atomicity violation?**
A:
- *Race condition*: the result depends on the timing or interleaving of threads, e.g. two threads doing check-then-act.
- *Visibility bug*: one thread's write is never seen by another, because of CPU caches, compiler
  hoisting, or JIT reordering. Classic example: `while (!stop) {}` spinning forever on a non-volatile flag.
- *Atomicity violation*: a compound operation (`read-modify-write`) gets interleaved.
Fixes: `volatile` for visibility-only flags, atomics (CAS) for single-variable updates,
locks for multi-variable invariants, and **pushing atomicity into the database** when state is
shared across JVMs. This repo does the last one for idempotency: `INSERT ... ON CONFLICT (key)
DO NOTHING` instead of `SELECT` then `INSERT`. A JVM lock is useless across 8 pods.

**Q: `ArrayList` vs `LinkedList` internally — which would you pick and why?**
A: `ArrayList` is a resizable array. Random access is O(1), append is amortized O(1) (it grows by about 1.5× and copies),
and middle insert/remove is O(n) because of shifting, but `System.arraycopy` is very fast. `LinkedList`
is a doubly linked list. Insert/remove at a known node is O(1), but `get(i)` is O(n), every element has
node overhead (~24+ bytes), and pointer chasing is cache-unfriendly.
- In practice `ArrayList` (or `ArrayDeque` for queue/stack use) wins almost always, because
  CPU cache locality dominates big-O at realistic sizes. Use `LinkedList` almost never.
- Pre-size (`new ArrayList<>(n)`) when you know the size. `OutboxRelay` does this for its futures list.

**Q: How does `HashMap` work internally? Relationship between `equals` and `hashCode`?**
A: An array of buckets (default capacity 16, load factor 0.75). The bucket index is
`(n-1) & spread(hash)`, where `spread = h ^ (h >>> 16)` mixes high bits into low bits.
Collisions chain in a linked list. When a bucket reaches **8** entries *and* the table has ≥ **64**
buckets, it becomes a red-black tree (O(log n) worst case). It goes back to a list at 6. When
size > capacity × load factor, the table doubles and entries split between `i` and `i + oldCap`.
- **Contract:** `a.equals(b)` ⇒ `a.hashCode() == b.hashCode()`. The reverse doesn't hold.
  Break it and `get` misses entries you `put`.
- **Mutable keys** are a bug factory. If a field used in `hashCode` changes after insertion, the
  entry is "lost" in the wrong bucket. Records make good keys because they are immutable and get
  `equals`/`hashCode` generated.
- `HashMap` is not thread-safe. Concurrent resize could corrupt it (an infinite loop in Java 7). Use
  `ConcurrentHashMap`.

**Q: How does `ConcurrentHashMap` achieve thread safety (Java 8+)?**
A: There is no global lock. An empty bin is filled with a **CAS**. A non-empty bin is updated while
`synchronized` on that bin's head node, so contention is per-bin. Reads are lock-free
(volatile reads). Resize is cooperative: multiple threads help transfer bins. It doesn't allow `null`
keys or values, because `get()` returning `null` must mean "absent".
- `computeIfAbsent` is atomic per key, but the mapping function runs while the bin lock is held.
  Keep it short and **never** update the same map inside it (deadlock risk or
  `IllegalStateException: Recursive update`).
- In the repo: `CacheInvalidationService.caches` (cache registry) and
  `NearCache.evictionCounters` (lazily created Micrometer counters via `computeIfAbsent`).

**Q: What's wrong with removing from a list while iterating a for-each loop?**
A: The enhanced for-loop uses an `Iterator`. Structural modification through the list (not the
iterator) bumps `modCount`, and the iterator's fail-fast check throws
`ConcurrentModificationException`. Fixes: `iterator.remove()`, `list.removeIf(pred)`, or
collect and remove later. Also, `List.of(...)` / `Collectors.toUnmodifiableList()` are
immutable, so `remove` throws `UnsupportedOperationException`. Fail-fast is best-effort and
not a concurrency guarantee. For concurrent iteration use `CopyOnWriteArrayList`
(read-heavy) or concurrent collections, which have weakly consistent iterators.

**Q: Checked vs unchecked exceptions — and why does it matter in Spring?**
A: Checked exceptions (`IOException`) must be declared or handled. Unchecked ones (`RuntimeException`)
don't. Modern APIs prefer unchecked exceptions, plus translation at boundaries (Spring translates
`SQLException` into the `DataAccessException` hierarchy).
- **Spring `@Transactional` rolls back on unchecked exceptions and `Error` by default, not on checked
  exceptions.** If a service throws a checked exception, the transaction *commits* unless you set
  `rollbackFor`. This is a classic production bug.
- Don't swallow exceptions. When you deliberately degrade, log with context. `NearCache` logs
  "Fail-open: L2 cache read error..." and falls back to the DB.

**Q: Records, sealed types, pattern matching — what modern Java features do you use, and why?**
A:
- **Records** (16): immutable data carriers with generated `equals`/`hashCode`/`toString`, and a
  compact constructor for validation and defaults. All event contracts here are records, e.g.
  `OrderPlacedEvent`. Its compact constructor defaults `version = 1` and `aggregateVersion = 1L`, which
  helps backward-compatible deserialization. Config is records too: `OutboxProperties`
  with `@DefaultValue` and `@Validated`.
- **Sealed interfaces** (17) and **pattern matching for `switch` / record patterns** (21): closed
  domain decisions (`IdempotencyDecision`) with compiler-checked exhaustiveness.
- **Switch expressions** (14): `NearCache.onL1Removal` switches on `RemovalCause` with `->` arms.
- **Text blocks** (15): SQL in `JdbcIdempotencyRepository` and `ReplicaLagMonitor`.
- **Virtual threads** (21): evaluated behind a flag only (see section 3).

**Q: Generics: type erasure, wildcards, and PECS?**
A: Generic type parameters are erased at compile time, so at runtime a `NearCache<Long, OrderDto>` is
just `NearCache`. You can't do `new T()` or `instanceof List<String>`, and you need
`Class<T>` tokens or `TypeReference` to deserialize. This repo passes `Class<V> valueClass` into
`NearCache` and uses `TypeReference<List<OrderItemDto>>` in `OrderQueryService` for that reason.
- **PECS**: Producer `extends`, Consumer `super`. Read from `List<? extends Number>` and
  write into `List<? super Integer>`.
- Erasure in the repo: `CacheInvalidationService` keeps `Map<String, NearCache<?, ?>>`, and an
  invalidation event carries the key as a `String`. It can't know the key type, so it scans
  and compares `k.toString()` (with an unchecked cast). A cleaner design gives each cache a
  `Function<String, K>` key parser.

**Q: `String` immutability, the string pool, and `StringBuilder`?**
A: `String` is immutable, which makes it thread-safe, safe as a key, and cacheable. Its hash is cached.
Literals are interned in the string pool. Concatenation in a loop creates garbage. Use
`StringBuilder` (not thread-safe, fast). Since Java 9, `+` compiles to `invokedynamic`
(`StringConcatFactory`), so single expressions are fine and loops are still bad.
Compact strings (Java 9) store Latin-1 text as 1 byte per char.

**Q: `Optional` — good and bad uses?**
A: Use it as a **return type** to say "maybe absent". Don't use it for fields, parameters,
or collections (return an empty collection instead), and never call `get()` without checking.
Prefer `map`/`orElseThrow`/`orElseGet` (lazy) over `orElse` (eager, evaluates the argument even when present).

**Q: How do you design an immutable class?**
A: `final` class (or a record), `private final` fields, no setters, defensive copies of mutable
inputs and outputs (`List.copyOf`), and no `this` escape during construction. Benefits: thread safety
without locks, safe sharing in caches, and simpler reasoning. Cached DTOs here (`ProductDto`,
`OrderDto`) are records. That matters because the L1 cache hands the **same instance** to many
request threads, and a mutable cached object would be a cross-request data race.

---
## 3. Concurrency & the JVM — Q&A

**Q: Explain the Java Memory Model and *happens-before*.**
A: The JMM defines when a write by one thread is guaranteed to be visible to a read in another.
Without a happens-before edge, the compiler, JIT, and CPU may reorder operations or cache values.
Edges include: program order within a thread; unlock → subsequent lock of the same monitor;
volatile write → subsequent volatile read; `Thread.start()` → actions in the started thread;
thread actions → `join()` returning; completion of a constructor's `final` field writes →
any thread that sees the reference; `ExecutorService.submit` → task execution; `Future.get`
after the task completes.
- Classic broken pattern: double-checked locking without `volatile`. Another thread can see a
  partially constructed object. Fix it with `volatile`, or better, the holder-class idiom or an enum.

**Q: `synchronized` vs `ReentrantLock` vs `ReadWriteLock` vs `StampedLock`?**
A:
- `synchronized`: simple, block-scoped, released automatically even on exceptions, and the JVM optimizes it heavily.
  It can't time out, can't be interrupted while waiting, and has no fairness option.
- `ReentrantLock`: `tryLock(timeout)`, `lockInterruptibly()`, optional fairness, and multiple
  `Condition`s. You must `unlock()` in `finally`.
- `ReentrantReadWriteLock`: many readers or one writer. Use it for read-heavy shared state. Writer starvation
  is possible.
- `StampedLock`: optimistic reads (`tryOptimisticRead` + `validate`) that take no lock at all
  for read-mostly data. It is not reentrant.
- **Virtual-thread note (Java 21):** blocking inside `synchronized` *pins* the carrier
  thread. JDK 24 (JEP 491) largely fixes this, but on 21 prefer `ReentrantLock` around blocking I/O.
  This is one reason ADR-0027 keeps virtual threads behind a flag.

**Q: CAS and atomics — how do they work and when do they fail?**
A: Compare-and-swap is a CPU instruction (`cmpxchg`) that sets a value only if it still equals
the expected value. `AtomicInteger.incrementAndGet()` loops on CAS. It is lock-free, but under heavy
contention the threads spin and retry.
- `LongAdder` stripes the counter across cells, so it is much faster for write-heavy counters (metrics),
  but its sum is not a point-in-time snapshot.
- ABA problem: the value changes A→B→A and CAS succeeds incorrectly. Fix it with `AtomicStampedReference`.
- In the repo: `ReplicationLagTracker` uses `AtomicLong currentLagMs` + `AtomicBoolean
  replicaHealthy`, written by the scheduler thread and read by every request thread. Each field is
  individually visible, but the *pair* isn't updated atomically. That is acceptable here because the read
  `replicaHealthy && lag <= max` tolerates a momentarily mixed view. Saying that explicitly
  is a senior signal. `AdaptiveLoadShedderFilter` uses `AtomicInteger inFlightDegradable`,
  incremented and decremented in `try/finally`.

**Q: How do you size thread pools? What about bounded queues and rejection?**
A: For CPU-bound work use roughly `cores (+1)`. For I/O-bound work use `cores × (1 + wait/compute)` (Goetz).
Better still, size from Little's law: *concurrency = throughput × latency*. Always use **bounded**
queues plus an explicit rejection policy (`CallerRunsPolicy` gives backpressure). Unbounded
queues hide overload until you hit an OOM, and `Executors.newFixedThreadPool` uses an unbounded queue.
- Pools must be consistent end to end. In this repo, Tomcat allows **500** threads per pod
  (`server.tomcat.threads.max`), but the DB pools are **30 primary + 20 replica**. Under DB-bound
  load, up to 450 request threads queue on `HikariPool.getConnection()` and give up after
  `connectionTimeout` (5 s in `DataSourceRoutingConfig`). ADR-0027 makes the Hikari pool the
  *intentional* backpressure boundary. The follow-up question is whether we fail fast enough:
  consider a shorter connection timeout on read paths and load shedding *before* the pool saturates.

**Q: `CompletableFuture` — composition, thread pools, and pitfalls?**
A: Use `thenApply` (map), `thenCompose` (flatMap), `thenCombine`, `allOf`/`anyOf`, `exceptionally`/
`handle`/`whenComplete`. The `*Async` variants without an executor run on `commonPool()`, so pass
a dedicated executor for blocking work. Pitfalls: exceptions arrive wrapped in `CompletionException`,
forgetting timeouts (`orTimeout`, `completeOnTimeout`), and blocking `get()` on request
threads.
- In the repo: `OutboxRelay.publishBatch` fires up to 500 `kafkaTemplate.send()` futures (the
  producer batches them), then calls `CompletableFuture.allOf(...).get(10, SECONDS)`. Successful IDs
  are collected in a `Collections.synchronizedList` from the producer's I/O callback thread,
  and only those rows get `markPublished`. Failures stay unpublished and are retried on the
  next tick. The interrupt handling is correct: on `InterruptedException` it restores the flag
  (`Thread.currentThread().interrupt()`) and stops the loop.

**Q: Deadlock — conditions, detection, prevention?**
A: Coffman conditions: mutual exclusion, hold-and-wait, no preemption, circular wait. Prevent it by
breaking one: a global lock ordering, `tryLock` with timeout and back-off, or holding fewer
locks for less time. Detect it with a thread dump (`jcmd <pid> Thread.print`, which reports
"Found one Java-level deadlock"). Databases detect lock cycles and abort one transaction
(Postgres `deadlock_timeout`, SQLSTATE `40P01`), so the application must retry.
Related failure modes: *livelock* (threads keep reacting to each other and make no progress) and
*starvation* (unfair locks, pool exhaustion).

**Q: ThreadLocal / MDC — why are they dangerous in pooled environments?**
A: Pool threads are reused. A `ThreadLocal` (and SLF4J MDC, which is ThreadLocal-backed)
that is not cleared leaks into the *next* request: you get the wrong correlation ID in logs, or
the wrong tenant or security context. That is a data-leak class of bug. It also leaks memory through
ClassLoader references on redeploys.
- The repo's rule (ADR-0027 §3.3): every entry point sets MDC and **removes it in `finally`**.
  `CorrelationIdFilter` puts `correlationId`, `instanceId`, `traceId`, `spanId` and removes them in
  `finally`. `OutboxRelay` does the same per event. Kafka listeners use
  `CorrelationIdRecordInterceptor`.
- Context does **not** cross thread boundaries automatically. `VirtualThreadsQualificationTest`
  demonstrates that MDC is *not* inherited by a new virtual thread, and that a Spring transaction is not
  inherited either. Use explicit propagation (Micrometer `context-propagation`,
  `ContextSnapshot`, decorating executors).

**Q: Virtual threads (Java 21) — should we switch? What breaks?**
A: Virtual threads are cheap (~KB-sized stacks on the heap) and scheduled on a small `ForkJoinPool` of
carrier threads. Blocking I/O unmounts the virtual thread instead of blocking an OS thread. This is ideal for
high-concurrency, I/O-bound, thread-per-request code without rewriting to reactive.
- What doesn't change: **downstream capacity**. With 10k virtual threads and a 30-connection
  Hikari pool, you've just moved the queue to `getConnection()`. You still need semaphores,
  bulkheads, and load shedding.
- Risks on 21: pinning in `synchronized` blocks and native frames (some JDBC/Kafka client code),
  ThreadLocal-heavy libraries (one copy per virtual thread, so memory grows), and "don't pool virtual threads".
- Repo decision (ADR-0027): **platform threads stay the production default**. Virtual threads are allowed
  only via `spring.threads.virtual.enabled=true` for qualification, and promotion requires evidence:
  no pinning at 10k VUs, no Hikari acquisition timeouts, preserved transaction/MDC/trace semantics, and
  graceful shutdown within 30 s. Reactive (WebFlux/R2DBC) was rejected because it breaks the JDBC
  `SKIP LOCKED` outbox and Spring's thread-bound transactions.

**Q: JVM memory layout — what lives where?**
A: **Heap** (objects: young gen = Eden + 2 Survivors, old gen; G1 manages it as regions),
**Metaspace** (class metadata, native memory), **thread stacks** (~1 MB per platform thread by default,
so 500 Tomcat threads is about 0.5 GB of *reserved* stack), **code cache** (JIT output), **direct /
off-heap buffers** (NIO, Netty/Lettuce, Kafka client). Container memory = heap + all of
the above. Setting `-Xmx` equal to the container limit **will get you OOMKilled**.
- In containers, size the heap with `-XX:MaxRAMPercentage` (e.g. 70–75%) instead of a fixed `-Xmx`, and
  leave headroom for native memory. Our pods have a 1 GiB limit (`values.yaml`), and
  `K8sContainerOOMKilled` alerts if we get it wrong. Diagnose native memory with
  `-XX:NativeMemoryTracking=summary` + `jcmd <pid> VM.native_memory`.

**Q: How does garbage collection work? G1 vs ZGC vs Parallel?**
A: GC reclaims objects that are unreachable from **GC roots** (thread stacks/locals, static fields,
JNI refs, JVM internals). Generational hypothesis: most objects die young, so collect young gen
often and cheaply (copying), and old gen rarely.
- **G1** (default): region-based, aims for a pause target (`MaxGCPauseMillis`, default 200 ms),
  concurrent marking, mixed collections. A good general default.
- **ZGC** (generational since 21, `-XX:+UseZGC -XX:+ZGenerational`): sub-millisecond pauses that
  don't grow with heap size, at some throughput and CPU cost. Consider it when GC pauses
  show up in p99 latency.
- **Parallel**: maximum throughput with longer stop-the-world pauses. Good for batch jobs.
- For a p95 < 200 ms SLO with 1 GB heaps, G1 is fine. Measure with GC logs
  (`-Xlog:gc*`) and JFR before tuning, and tune allocation rate first (fewer temporary objects).

**Q: Walk me through diagnosing a memory leak or a CPU spike in production.**
A: Memory:
1. Confirm a trend: old-gen-after-GC keeps rising, GC frequency increases, and eventually
   `OutOfMemoryError` or OOMKill.
2. Capture: `jcmd <pid> GC.heap_info`, `jcmd <pid> GC.class_histogram` (cheap, compare two
   over time), and a heap dump (`jcmd <pid> GC.heap_dump /diagnostics/heap.hprof`, which pauses the JVM, so take
   the pod out of rotation first). Set `-XX:+HeapDumpOnOutOfMemoryError`.
3. Analyze in Eclipse MAT: dominator tree, "path to GC roots". Typical culprits: unbounded caches
   or maps, listeners never removed, ThreadLocals, static collections, large result sets.
4. Repo safeguards: every Caffeine cache has `maximumSize` (10k / 2k / 50k entries, and the rate
   limiter's per-client map has 100k). Pods mount a `/diagnostics` emptyDir for dumps, and
   `performance/jvm/capture-jvm-diagnostics.sh` wraps `jcmd`/JFR collection.

CPU:
1. Use `top -H` to find the hot thread, convert its ID to hex, and match it to `nid` in a thread dump.
2. Or record JFR (`jcmd <pid> JFR.start duration=60s filename=/diagnostics/rec.jfr`), open it in JMC, and
   look at hot methods, allocation pressure, lock contention, and GC.
3. Take three thread dumps a few seconds apart. Threads stuck in the same frame indicate a lock
   or I/O wait. Many threads in `HikariPool.getConnection` mean DB pool exhaustion.

**Q: What is the JIT and why does warm-up matter?**
A: Bytecode is first interpreted. Hot methods are compiled by C1 (fast, with profiling) and then C2
(optimized, speculative inlining, escape analysis). A freshly started pod is slow until it warms up.
That's why the Helm chart uses a **startupProbe** (`failureThreshold: 30 × 3s`) before
liveness kicks in, and why load tests should discard a warm-up window. Mitigations: CDS/AppCDS,
CRaC, or a warm-up traffic phase before marking the pod ready.

---

## 4. Spring / Spring Boot — Q&A

**Q: Spring vs Spring Boot?**
A: Spring Framework provides the IoC container, DI, AOP, transaction abstraction, MVC, and so on. Spring
Boot is opinionated on top of it: **auto-configuration**, starter dependencies, embedded
server, externalized config with profiles, Actuator, and production defaults. Boot removes wiring
boilerplate, but you must understand what it auto-configures in order to override it safely.
This repo is on **Spring Boot 4.0 / Framework 7**, which brings **Jackson 3** (the `tools.jackson.*`
packages, e.g. `JacksonException` registered as non-retryable in the Kafka error handler).

**Q: Dependency injection — why constructor injection?**
A: Constructor injection makes dependencies explicit, enables `final` fields (immutability and
safe publication), fails fast on missing beans at startup, makes circular dependencies visible
instead of hiding them, and makes unit tests trivial (`new OrderService(fakeRepo, ...)`).
Field injection (`@Autowired` on fields) hides dependencies and requires reflection in tests.
Every class in this repo uses constructor injection, and optional collaborators use
`@Autowired(required = false) @Nullable`. Example: `CatalogService` runs without Redis or
Kafka in tests.

**Q: Describe the bean lifecycle. Where do proxies come from?**
A: Bean definition → instantiate → populate properties/dependencies → `*Aware` callbacks →
`BeanPostProcessor.postProcessBeforeInitialization` → `@PostConstruct` /
`InitializingBean.afterPropertiesSet` / init-method → `postProcessAfterInitialization`
(**AOP proxies are created here**: `@Transactional`, `@Async`, `@Cacheable`) → in use →
`@PreDestroy` / `DisposableBean` on context close.
- Spring Boot defaults to **CGLIB class-based proxies** (`proxyTargetClass=true`). `final` classes
  and methods can't be advised, and `private` methods are never intercepted.

**Q: How does Spring Boot auto-configuration work? How do you debug it?**
A: Auto-configuration classes are listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and loaded
after user configuration. Each one is guarded by conditions: `@ConditionalOnClass`,
`@ConditionalOnMissingBean`, `@ConditionalOnProperty`, `@ConditionalOnBean`,
`@ConditionalOnWebApplication`. It backs off when you define your own bean.
- Debug it with `--debug` or `debug=true` (the conditions evaluation report), or `/actuator/conditions`.
- Overrides in this repo:
  - `DataSourceRoutingConfig` defines its own `primaryDataSource`, `replicaDataSource`, and a
    `@Primary` routing `DataSource`, so Boot's `DataSourceAutoConfiguration` backs off.
    **Consequence:** the `spring.datasource.hikari.*` block in `application.yml` (pool 50,
    `leak-detection-threshold: 5000`) does **not** apply to those pools. They are configured in
    code (30/20 connections, `connectionTimeout` 5 s, `maxLifetime` 30 min). This trap is worth
    mentioning in an interview.
  - `TracingConfiguration` defines a Brave `Tracing` with `@ConditionalOnMissingBean(Tracing.class)`.
  - `KafkaConfig` defines its own producer and consumer factories and listener container factory.

**Q: What does `@Transactional` do? Explain the propagation levels.**
A: A proxy intercepts the call, gets or creates a transaction through the
`PlatformTransactionManager`, binds the connection to the thread (`TransactionSynchronizationManager`),
and commits on return or rolls back on unchecked exceptions.
- `REQUIRED` (default): join the current transaction or start one. `REQUIRES_NEW`: suspend the current one and start an
  independent transaction on a *new connection*, which needs two pool connections at once and can deadlock the pool under
  load. `NESTED`: a savepoint inside the current transaction. `SUPPORTS`, `NOT_SUPPORTED`, `MANDATORY`, `NEVER`.
- Other attributes: `readOnly`, `isolation`, `timeout`, `rollbackFor`/`noRollbackFor`.
- In the repo: `IdempotencyService.evaluate/complete/fail` are `REQUIRES_NEW`, so the idempotency
  reservation commits **independently** of the order transaction. The `IN_PROGRESS` row is visible
  to concurrent duplicates immediately, and a failed order doesn't roll back the bookkeeping.
  `OrderService.createOrder` is `REQUIRED` and puts the order insert and the outbox insert in one
  transaction, which is the core of the outbox pattern.

**Q: `@Transactional(readOnly = true)` — what does it actually do here?**
A: Generally it's a hint: Hibernate skips dirty checking, and the JDBC driver may set `Connection.setReadOnly`.
**Here it's load-bearing.** `TransactionRoutingDataSource.determineCurrentLookupKey()` checks
`TransactionSynchronizationManager.isCurrentTransactionReadOnly()`. Read-only transactions go to the
**replica** if `ReplicationLagTracker.isLagAcceptable()` (healthy and lag ≤ 100 ms). Otherwise
they go to the **primary**.
- **Why `LazyConnectionDataSourceProxy` wraps it:** the transaction manager normally grabs a
  connection *at transaction begin*, before the read-only flag is bound, so routing would always
  see "not read-only". The lazy proxy defers the physical connection until the first statement,
  and by then the flag is set. Missing this is the #1 bug in read/write-splitting setups.

**Q: The `@Transactional` self-invocation problem — why and how to fix it?**
A: The transaction lives in the *proxy*. Calling `this.otherMethod()` bypasses the proxy, so
the annotation on `otherMethod` is ignored. Fixes: move the method into another bean (cleanest), inject
the proxy of self (`@Lazy` self-reference), use `TransactionTemplate` programmatically, or use AspectJ
weaving. The same applies to `@Async` and `@Cacheable`. This repo avoids the trap by putting
`REQUIRES_NEW` methods in a separate bean (`IdempotencyService`) that the controller calls.

**Q: Bean scopes, and what goes wrong with them?**
A: `singleton` (default, one per container: **must be thread-safe and stateless or properly
synchronized**), `prototype` (new instance per lookup, and Spring doesn't manage its destruction), and web
scopes `request`, `session`, `application`, `websocket`. Injecting a prototype or request-scoped bean
into a singleton captures one instance forever unless you use a scoped proxy, `ObjectProvider`, or
`@Lookup`. All services here are singletons with only thread-safe internal state (Caffeine caches,
atomics, `ConcurrentHashMap`).

**Q: How are servlet filters ordered here, and why does order matter?**
A: `@Order` on `OncePerRequestFilter` beans: `AdaptiveLoadShedderFilter` is
`HIGHEST_PRECEDENCE + 10`, `ClientRateLimitFilter` is `HIGHEST_PRECEDENCE + 15`, and `CorrelationIdFilter` is
`LOWEST_PRECEDENCE`. Shedding first is deliberate: rejected requests should cost as little
as possible. Trade-off: a shed or rate-limited 429 is returned **before** a correlation ID is
assigned, so those responses carry no `X-Correlation-Id`. If support needs to trace rejected
calls, move correlation earlier (it's cheap).

**Q: How do you handle errors consistently in a REST API?**
A: Use `@RestControllerAdvice` per bounded context to map domain exceptions to status codes and a stable
error body, without leaking stack traces. `OrderErrorHandler` maps validation → 400,
`IdempotencyConflictException` → **409**, `IdempotencyPayloadMismatchException` → **422**,
unexpected errors → 500. Consider RFC 9457 `ProblemDetail` (built into Spring 6+) for a standard
format.

**Q: How is configuration managed and validated?**
A: Use typed `@ConfigurationProperties` records with `@Validated` and `@DefaultValue`, e.g.
`OutboxProperties(topic, relayIntervalMs=100, claimLimit=500)`, `LoadSheddingProperties`,
`ClientRateLimitProperties`. Environment variables override YAML (`${PRIMARY_DB_POOL_SIZE:30}`). Profiles
switch behavior (`local` = human-readable logs, `!local` = JSON). Invalid config fails at startup,
not at 3 a.m.

**Q: `@Scheduled` — what is the default threading model and what can go wrong?**
A: Spring Boot's auto-configured `ThreadPoolTaskScheduler` has **one thread by default**
(`spring.task.scheduling.pool.size=1`). `fixedDelay` waits for the previous run to finish, while `fixedRate`
can pile up runs. In this repo `OutboxRelay` (100 ms fixedDelay, loops while batches are full),
`ReplicaLagMonitor` (1 s), and the pruning services share that scheduler. If the relay drains a large
backlog, **replica-lag sampling stalls** and routing decisions use a stale lag value.
Mitigation: a dedicated scheduler or executor for the relay, or a bigger pool. In a multi-pod
deployment every pod runs every `@Scheduled` job, so jobs must be safe to run concurrently (DB
locking) or need leader election (ShedLock).

**Q: Graceful shutdown and health probes in Spring Boot?**
A: `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`: on
SIGTERM, Tomcat stops accepting new requests, in-flight requests get up to 30 s to finish, then
beans are destroyed (Kafka containers stop and commit, Hikari closes).
Actuator probes: `/actuator/health/liveness` (should the process be restarted?) vs
`/actuator/health/readiness` (should it receive traffic?). Here both groups include only
`livenessState` / `readinessState`.
- **Never put DB or Kafka checks in liveness.** A database blip would restart every pod at once,
  which is a cascading failure. Putting them in readiness is debatable: it takes pods out of rotation when a dependency is down,
  but if *all* pods depend on the same DB, everything goes unready together. This repo prefers to stay
  ready and degrade (fail-open caches, load shedding).

**Q: Spring Data JDBC vs JPA/Hibernate vs jOOQ — why this combination?**
A: (ADR-0009) **Spring Data JDBC** handles simple aggregate persistence: no lazy loading, no
session or dirty checking, and the aggregate is saved as a unit. `order_items.orders_key` preserves
line-item order. **jOOQ** handles type-safe SQL wherever SQL matters: `FOR UPDATE SKIP LOCKED`,
`INSERT ... ON CONFLICT ... DO UPDATE ... WHERE version <= ?`, Postgres JSONB casts. jOOQ code
is **generated from the Flyway migrations** (`DDLDatabase`), so no running DB is needed at build time and
schema drift fails compilation. JPA was avoided because of N+1 and lazy-loading surprises, opaque
SQL, and a poor fit for Postgres-specific features.

---
## 5. Caching — Q&A

**Q: Describe the caching strategy end to end.**
A: Two tiers behind one class, `NearCache<K,V>` (separate copies in `app` and `order-query`):
- **L1**: in-process Caffeine with `maximumSize` + `expireAfterWrite`. Sub-microsecond, per-pod.
- **L2**: Redis via `StringRedisTemplate`. Values are JSON strings, keys look like `"<cacheName>:<key>"`, and
  the L2 TTL is **10 minutes**. Shared across pods, so it survives pod restarts and scale-out.

| Cache | Service | L1 size / TTL | L2 TTL |
|---|---|---|---|
| `catalog_products` | `CatalogService.getProductById` | 10,000 / 60 s | 10 min |
| `catalog_list` | `CatalogService.listProducts` (key `query:page:size`) | 2,000 / 30 s | 10 min |
| `order_query` | `OrderQueryService.getOrder` | 50,000 / 30 s | 10 min |

Read path (`NearCache.get`): `l1.getIfPresent` → on a miss, `l1.get(key, loader)`, where the loader
tries **L2** (deserialize on a hit) and otherwise calls the **DB supplier** and writes the result to L2. The value
returned by the mapping function populates L1. This is *cache-aside* wrapped to look like
*read-through*.

**Q: Cache-aside vs read-through vs write-through vs write-behind — trade-offs?**
A:
- *Cache-aside*: the app reads the cache, and on a miss loads from the DB and fills the cache. Writes go to the DB and
  **invalidate** the cache. Simple and resilient (the cache is optional), but it has a stale-read window
  and miss storms. **This is what we use.**
- *Read-through*: the cache library loads on a miss. Same consistency as cache-aside, cleaner code.
- *Write-through*: writes go to the cache and the DB synchronously. Fresh reads, slower writes, and a dual-write
  inconsistency on partial failure.
- *Write-behind*: write the cache now, flush to the DB asynchronously. Very fast writes, but you can lose data if the cache dies.
  Unacceptable for orders ("zero intentional data loss").
- *Refresh-ahead*: Caffeine's `refreshAfterWrite` reloads hot keys asynchronously before they expire.
- **Invalidate rather than update** on writes. Updating the cache from the writer races with
  concurrent writers, while deleting is idempotent.

**Q: How do you avoid a cache stampede / thundering herd?**
A: In-JVM: Caffeine's `cache.get(key, mappingFunction)` is **atomic per key**. Concurrent misses
for the same key block on one computation (single-flight), so one pod issues at most one DB load per
key. ADR-0027 relies on this explicitly. Across N pods you can still get N loads, and L2
absorbs most of them. Further options when a key is extremely hot:
- a distributed lock (`SET key NX PX`) around the load, with followers briefly waiting or serving stale,
- **probabilistic early expiration** (XFetch) or `refreshAfterWrite`, so keys refresh before they expire,
- **TTL jitter**, so keys written together don't expire together (our TTLs are fixed, a possible improvement),
- serve-stale-on-error.

**Q: Cache penetration, breakdown, avalanche — what are they and how do you defend?**
A:
- *Penetration*: repeated lookups for keys that don't exist (404s or attacks) always reach the DB. Caffeine
  doesn't store `null`, so `getProductById(nonexistent)` hits the DB **every time** here. Defenses:
  negative caching (a sentinel with a short TTL), a Bloom filter of valid IDs, input validation, and rate limiting.
- *Breakdown (hot key expiry)*: one hot key expires and a spike follows. Defenses: single-flight (we have it per JVM) and refresh-ahead.
- *Avalanche*: many keys expire at once, or the cache tier dies. Defenses: TTL jitter, multi-tier caching (L1 still
  serves if Redis dies), and circuit breakers and load shedding in front of the DB.

**Q: How is cache consistency maintained after writes?**
A: **Event-driven invalidation over Kafka**, plus TTL as the safety net.
- Order read side: when `OrderPlacedProjection` / `OrderCancelledProjection` actually change a
  row, they call `OrderQueryService.evictOrder(id)`. That evicts **local L1 + shared L2** and
  publishes a `CacheInvalidationEvent` to `order-cache-evict`. Each pod's
  `CacheInvalidationListener` subscribes with a **random consumer group ID**
  (`#{T(java.util.UUID).randomUUID()}`), which turns a Kafka topic into **pub/sub broadcast**: every pod
  gets every message. Each pod then calls `evictLocal` (L1 + L2 delete again).
- Catalog: `CatalogService.evictProduct` evicts the product and clears the list cache (a product change
  can affect any page), then broadcasts on `catalog-cache-evict`.
- Why L2 is deleted *again* on every pod (code comment in `NearCache.evictLocal`): an in-flight
  Caffeine load on some pod could repopulate L2 with the old value just before the invalidation
  arrives. Repeating the shared delete on every consumer closes most of that race.
- **Be honest about the guarantee:** this is *eventual* consistency with a small window, not
  "zero stale reads". Publishing the invalidation is fire-and-forget (`kafkaTemplate.send`, failure
  only logged). If the event is lost, other pods serve stale L1 for up to the L1 TTL (30–60 s) and
  stale L2 for up to 10 min. The `CacheInvalidationLagHigh` alert watches consumer lag on the evict topics.

**Q: Explain the classic read/write race that leaves stale data in a cache-aside setup.**
A: Reader R misses and reads the *old* value from the DB (or from a lagging replica). Writer W commits the new value and
deletes the cache entry. *Then* R writes the old value into the cache, and it stays stale until the TTL expires.
Mitigations: delete *after* commit (we evict after the projection write), delayed double-delete
(we effectively double-delete through the broadcast), versioned cache entries (only overwrite if the
version is newer, and we already have an aggregate `version` column in the read model), short TTLs,
or CDC-driven invalidation (Debezium) so invalidation ordering follows the WAL.
- Read replicas make this worse: a miss served from a replica within the 100 ms lag budget
  can cache a pre-write value. That is one reason the lag threshold is tight.

**Q: What happens if Redis is down?**
A: **Fail-open.** Every L2 call in `NearCache` is wrapped in `try/catch`, logs
`"Fail-open: L2 ..."`, and falls through to the DB. L1 keeps serving hot keys. Lettuce is configured
with `connect-timeout: 1s` and `timeout: 1s`, so a dead or slow Redis costs at most about 1 s per L2 call
instead of hanging a request thread.
- Senior follow-up: during an outage, *every* L1 miss still pays up to 1 s of timeout. That can
  blow the 200 ms SLO and multiply in-flight requests. Add a **circuit breaker** around L2 (open after N
  failures, skip L2 entirely, probe half-open), or use a much shorter command timeout (10–50 ms) on
  the read path. The DB must also be provisioned to survive a cold-cache stampede.
- Alerts: `RedisNodeDown`, `L2CacheMissRateSpike`. Redis runs as **one replica** in the Helm
  chart. That is a SPOF, but acceptable because it's a cache and the system fails open. For HA use Redis
  Sentinel or Cluster.

**Q: Any Redis anti-patterns you'd flag?**
A: `RedisL2CacheStore.deleteByPrefix` uses `KEYS prefix*`. `KEYS` is **O(N) over the whole
keyspace and blocks Redis's single thread**, so with millions of keys it stalls every client.
Replace it with `SCAN` + `UNLINK` in batches, or better, **namespace versioning**: keep a
`catalog_list:v{n}` prefix and bump `n` to "clear all" in O(1), letting old keys expire through TTL.
Other points: use pipelining for multi-key operations, use `UNLINK` (async free) instead of `DEL` for large values,
set `maxmemory` + `allkeys-lru`/`lfu`, and avoid big values or hot keys (shard the hot ones).

**Q: Eviction policy — LRU? LFU?**
A: Caffeine uses **Window TinyLFU**: a small LRU "window" admits new entries, and a frequency
sketch (Count-Min) decides whether a candidate deserves to evict a victim from the main
segmented-LRU space. It beats pure LRU on scan-heavy and one-hit-wonder workloads. (The repo's
eviction metric labels `RemovalCause.SIZE` as `reason="lru"`. It's really size-based W-TinyLFU.)

**Q: How do you observe cache effectiveness?**
A: Custom Micrometer counters in `NearCache`: `hyperscale.cache.gets{cache, level=L1|L2,
result=hit|miss}` and `hyperscale.cache.evictions{cache, reason=ttl|lru|mutation|event}`
(Prometheus names: `hyperscale_cache_gets_total`, ...). Hit ratio =
`hits / (hits + misses)` per level. Watch for an L2 miss-rate spike (Redis flushed or down) and
eviction reason `lru` (cache too small for the working set). Proof of value: the Phase 12 run held
**1,043 RPS with p95 1.91 ms** on product lookups.

**Q: What should *not* be cached?**
A: Data needing strong read-your-writes consistency (balances, inventory decisions, the
idempotency state machine, which lives in Postgres for exactly this reason), large or rarely reused
values, per-user data with high cardinality and low reuse, and anything whose staleness has
legal or financial impact. List and search results are cached here, but they are invalidated broadly on any product
change.

---

## 6. Kafka & Event-Driven Architecture — Q&A

**Q: Explain Kafka's core model — topics, partitions, offsets, consumer groups.**
A: A topic is split into **partitions**. Each partition is an append-only log, ordered by offset and replicated
to `RF` brokers (one leader plus followers, with the in-sync replica set **ISR**). Producers choose the partition by
key hash. A **consumer group** shares partitions: each partition is consumed by exactly one member
of the group, so max useful parallelism equals the partition count. Consumers commit offsets to
`__consumer_offsets`. Retention is time- or size-based, and consumers can rewind (replay).
- Repo topology: `order-placed`, `order-cancelled`, `inventory-reservation-failed`,
  `*-dlq`, `catalog-cache-evict`, `order-cache-evict`. HA topics are created with **3 partitions,
  RF=3, `min.insync.replicas=2`** (`performance/scripts/init-kafka-ha-topics.sh`). Kafka runs in KRaft
  mode (no ZooKeeper) as a 3-node StatefulSet.
- `spring.kafka.listener.concurrency: 3` matches the 3 partitions. More threads than partitions per
  group would sit idle. With 8 pods × 3 threads = 24 consumers for 3 partitions, **21 are idle**.
  To scale consumption you must add partitions (and keys then remap, which affects ordering during the
  change).

**Q: How does Kafka preserve ordering? What breaks it?**
A: Ordering is guaranteed **only within a partition**. Same key → same partition → ordered.
We key every order event by `aggregateId` (the order ID). Things that break it:
- producer retries with multiple in-flight requests **without idempotence**, which reorders batches. We set
  `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, `acks=all`, `retries=MAX`.
  With idempotence, brokers deduplicate by sequence number and preserve order with up to 5 in flight,
- **different topics for the same aggregate**. `OrderPlaced` goes to `order-placed` and `OrderCancelled` goes to
  `order-cancelled` (`OutboxRelay.resolveDestinationTopic`), so a consumer can see *Cancelled before
  Placed*,
- adding partitions (key → partition mapping changes),
- concurrent async sends within an outbox batch, where one fails and is retried later after a newer event succeeded.
**Our defense is in the consumer, not just the pipe:** the read model stores an aggregate
`version` (Placed = 1, Cancelled = 2), and the upsert only applies `WHERE version <= incoming`.
`OrderPlacedProjection` handles cancel-first arrival by *enriching the cancellation tombstone*
(fills in the items and `created_at` without regressing the version) and counts `events_out_of_order_total`.

**Q: Delivery semantics — at-most-once, at-least-once, exactly-once. What do we have?**
A:
- *At-most-once*: commit the offset before processing. Fast, can lose messages.
- *At-least-once*: process, then commit. A crash between the two causes redelivery. **That's our model**
  (`enable.auto.commit=false`, `AckMode.MANUAL_IMMEDIATE`, `acknowledgment.acknowledge()` after the
  DB write).
- *Exactly-once* (Kafka EOS): idempotent producer + transactions (`transactional.id`,
  `sendOffsetsToTransaction`) + consumers using `isolation.level=read_committed`. **It only covers
  Kafka → Kafka pipelines.** Once you write to Postgres or call an HTTP API, you need an *idempotent
  consumer* to get an exactly-once **effect**.
- How we get exactly-once effect: `inventory.reservations` has `UNIQUE (event_id, sku)`, and
  `recordIfAbsent` is a no-op on duplicates (counted as `outcome="duplicate"`). The read-model upsert is
  version-guarded, so re-applying the same event changes nothing. `cancelOrder` is a guarded state
  transition (`status != CANCELLED`). The general pattern is an **inbox / processed-events table** written
  in the same DB transaction as the side effect.

**Q: What do `acks=all` and `min.insync.replicas=2` buy you?**
A: `acks=all` means the leader acknowledges only after **all ISR members** have the record. With RF=3 and
`min.insync.replicas=2`, a write succeeds only if at least 2 replicas have it, so we survive the loss of any one broker
without data loss **and** without losing write availability. If the ISR drops below 2, producers get
`NotEnoughReplicas` and the outbox simply keeps events in Postgres until Kafka recovers (backlog
grows, nothing is lost). `unclean.leader.election.enable=false` (the default) prevents an out-of-sync
replica from becoming leader and truncating acknowledged data.

**Q: How are consumer failures handled? Blocking vs non-blocking retries?**
A: `DefaultErrorHandler` + `ExponentialBackOffWithJitter` (custom `BackOff`): **3 retries**, initial
200 ms, ×2, capped at 2 s, **±50% jitter** (so retries from many partitions don't sync up).
After that, `DeadLetterPublishingRecoverer` sends the record to **`<topic>-dlq`, same partition number**
(keeps per-key locality) and increments `events_dlq_total`. `JacksonException` and
`IllegalArgumentException` are **not retryable**: a malformed payload will never succeed, so it goes
straight to the DLQ (poison-pill isolation).
- Trade-off: these are **blocking retries**. While a record is retried, its partition is stuck
  (head-of-line blocking), and total backoff must stay well below `max.poll.interval.ms`, or the consumer
  gets kicked out of the group and triggers a rebalance. The alternative is **non-blocking retry topics**
  (`@RetryableTopic`: `-retry-0`, `-retry-1`, ... `-dlt`), which keep the partition flowing but
  **give up ordering** for retried records. For order lifecycle events, ordering matters, so
  short blocking retries + DLQ is the right default.

**Q: How do you recover messages from the DLQ?**
A: `order-query` exposes `POST /admin/dlq/replay {dlqTopic, targetTopic, maxRecords=100}`
(`DlqAdminController` → `DlqReplayService`). It creates a throwaway consumer (random group,
`earliest`, no auto-commit), polls once (2 s), and re-produces each record to the target topic with
headers `X-Redrive-Count` (incremented) and `X-Original-DLQ-Topic`. Records that already have **3 redrives** are
skipped (`dlq_replayed_events_total{outcome="skipped_max_redrives"}`), which prevents infinite
poison loops. HAProxy returns 403 for `/admin/dlq` from outside.
- Senior critique: because it never commits offsets and always starts from `earliest`, **every
  replay re-sends the whole DLQ from the beginning**. That is safe only because consumers are idempotent. It
  also polls only once, so large DLQs need several calls. Improvements: a persistent replay group
  with committed offsets, filtering by key or time, a dry-run mode, and authentication on the admin API.

**Q: What causes consumer rebalances and how do you minimize their impact?**
A: Members joining or leaving (deploys, scale events, crashes), exceeding `max.poll.interval.ms`
(slow processing or long retries), missed heartbeats (`session.timeout.ms`), and subscription changes.
Eager rebalancing stops the world for the whole group. Mitigations: the **cooperative-sticky assignor**
(incremental rebalancing), **static membership** (`group.instance.id`, so a rolling restart doesn't
trigger a rebalance within the session timeout), smaller `max.poll.records` (we use 500, so a slow
DB makes a 500-record batch dangerous), and graceful shutdown so offsets are committed on SIGTERM.
HPA scale events also cause rebalances, which is another reason to keep partitions ≥ peak consumers.

**Q: How do you monitor consumer health?**
A: `kafka_consumer_lag` is a gauge registered in `KafkaConfig`. It is the **max of the client
`records-lag-max` metric** across listener containers, and `KafkaConsumerLagHigh` fires when it is > 100 for 2 m.
Also `events_consumed_total{event_type, outcome=processed|duplicate|failed, consumer}`,
`events_out_of_order_total`, `events_dlq_total`, and on the read side `order_read_model_lag_seconds`
(seconds since the newest `updated_at`). Caveat: that last one measures *idle time*, not true lag. With no
traffic it grows forever. A better freshness SLI is `now() - event.createdAt` at projection time.
Client-side `records-lag-max` is also only visible while the consumer is alive. Broker-side lag
exporters (Burrow, `kafka_consumergroup_lag`) still work when consumers are dead.

**Q: How do you evolve event schemas without breaking consumers?**
A: ADR-0019: **additive-only, backward- and forward-compatible** JSON:
- new fields must be optional (nullable or defaulted). The record compact constructors default
  `version`, `aggregateVersion`, `sampled`,
- no renames or removals. Use a deprecation window with dual-write (producer writes old and new fields)
  and dual-read (consumer prefers new, falls back to old),
- breaking changes need a new event type or `version: 2` with a dedicated handler,
- consumers ignore unknown fields, and they read by field name (`root.get("orderId")`), which is tolerant by design.
- Deployment order: roll out consumers that understand the new shape **before** producers emit it.
- At larger scale, a schema registry (Avro/Protobuf with compatibility checks in CI) enforces this
  automatically instead of relying on discipline.

**Q: Why Kafka rather than RabbitMQ (or a DB queue)?**
A: (ADR-0006) Kafka gives a durable, replayable log (rebuild a read model by resetting offsets),
per-key ordering, high throughput, many independent consumer groups over the same stream
(inventory, order-query, and future analytics all read `order-placed`), and retention. RabbitMQ is
better for per-message routing, priorities, TTL/delayed delivery, and low-latency work queues, but
it has no replay or long retention by default. A Postgres table queue (`SKIP LOCKED`) is great at low volume
and gives transactional enqueue (that is what the outbox is), but it doesn't fan out cheaply or scale reads
like Kafka.

---
## 7. Distributed Systems Patterns — Q&A

**Q: Why the transactional outbox instead of publishing to Kafka directly?**
A: The **dual-write problem**: "commit the DB, then send to Kafka" (or the reverse) can't be atomic.
A crash between the two either loses the event (the order exists but nobody hears about it) or emits a
phantom event (the event says an order exists that was rolled back). 2PC/XA across Postgres and Kafka is not
practical. The outbox makes the event **part of the business transaction**:
```java
@Transactional
public OrderDto createOrder(List<OrderItem> items) {
  Order order = orderRepository.save(items);                       // business write
  outboxRepository.insert(orderId, "OrderPlaced", eventJson);      // same TX, same connection
  return toDto(order);
}                                                                  // commit both or neither
```
A relay then publishes asynchronously. The guarantee is **at-least-once**: the relay can crash after
`send` but before `markPublished`, causing a re-send, so consumers must be idempotent.
Alternatives: **CDC** (Debezium tailing the WAL, so there is no polling and ordering follows the WAL, at the cost of more infrastructure),
or *listen-to-yourself* (publish first, then the service consumes its own event to update the DB, which makes the
write itself eventually consistent).

**Q: Walk me through `OutboxRelay` in detail.**
A:
1. `@Scheduled(fixedDelay = 100ms)` → `claimDue(500)`:
   ```sql
   SELECT id, aggregate_id, event_type, payload, created_at, published_at
   FROM "order".outbox_events
   WHERE published_at IS NULL
   ORDER BY created_at
   LIMIT 500
   FOR UPDATE SKIP LOCKED
   ```
   backed by the **partial index** `idx_outbox_events_unpublished ON (created_at) WHERE
   published_at IS NULL`. It stays tiny because published rows drop out of it.
2. For each event: restore the trace context stored *inside the payload*
   (`traceId`, `parentSpanId`, `sampled`, `correlationId`), start an `outbox.publish` child span, set MDC,
   and `kafkaTemplate.send(topic, aggregateId, payload)` asynchronously, keyed by aggregate for ordering.
3. Wait for `allOf(futures)` up to 10 s, then `markPublished(successfulIds)` in a single
   `UPDATE ... WHERE id IN (...)`. Failed sends stay `NULL` and are retried on the next tick.
4. Loop immediately while the batch was full (drain mode), otherwise sleep 100 ms.
5. Housekeeping: `OutboxPruningService` deletes published rows older than **7 days** in batches of
   1,000 every hour (`storage_pruned_rows_total{table="outbox_events"}`). Backlog is visible via the
   `outbox_relay_lag` gauge (age of the oldest unpublished row).

**Q: How do multiple app replicas poll the outbox without duplicating events?**
A: The design intent is `FOR UPDATE SKIP LOCKED`: each replica locks a disjoint batch, and
other replicas skip locked rows instead of waiting, so throughput scales with pods and nobody blocks.
**Senior catch:** row locks only last as long as the *transaction*. `OutboxRelay.publishDueEvents`
is not `@Transactional`, so `claimDue` runs in autocommit mode and **the locks are released as soon as
the SELECT returns**. Two replicas polling at the same moment can claim the same unpublished rows and both
publish them. Correctness still holds (at-least-once plus idempotent consumers), but you get duplicate
traffic under multi-pod load. Fixes, depending on trade-offs:
- **Lease/claim column** (preferred): an atomic claim that persists past the statement:
  ```sql
  UPDATE outbox_events SET claimed_by = :pod, claimed_until = now() + interval '30 seconds'
  WHERE id IN (SELECT id FROM outbox_events
               WHERE published_at IS NULL AND (claimed_until IS NULL OR claimed_until < now())
               ORDER BY created_at LIMIT 500 FOR UPDATE SKIP LOCKED)
  RETURNING *;
  ```
  This needs no long transaction, and a crashed pod's lease expires.
- Wrap claim + publish + mark in one transaction. Simple, but it holds row locks and a pooled
  connection during Kafka I/O (up to 10 s), which competes with request traffic for the 30-connection pool.
- **Partition the outbox by key hash** and assign partitions to pods. That preserves per-aggregate order
  across the relay, which `SKIP LOCKED` alone does not.
- Single leader relay (ShedLock / advisory lock). Simple, but throughput is capped at one pod.

**Q: Why `FOR UPDATE SKIP LOCKED` over `pg_advisory_lock` or a single leader?**
A: `SKIP LOCKED` gives **work-stealing parallelism without coordination**: no leader election,
no lock service, and pods come and go freely. Advisory locks are good for "only one job runs at a time"
(leader-style) but are session-scoped (pooled connections make them tricky) and don't partition the work.
A single leader is simplest and preserves global order but limits throughput and adds failover lag.
Ordering caveat: `SKIP LOCKED` makes **no ordering guarantee across workers**. Events for the same
order can be published by different pods in a different order, and we rely on consumer-side versioning instead.

**Q: Explain the CQRS split. What do you gain and what do you pay?**
A: The command side (`app`) owns `"order".orders` / `order_items` and enforces invariants. The query side
(`order-query`) owns `order_query.order_read_model (order_id PK, status, items JSONB, version,
created_at, updated_at)`, a denormalized single-row-per-order document ready for `GET /orders/{id}`
without joins, and is cached in `NearCache` (50k entries / 30 s).
- Gains: independent scaling and deployment (reads usually dominate), a schema optimized for each side,
  query-side failures don't block order placement, and the read model can be **rebuilt by replaying**
  the topic from offset 0.
- Costs: **eventual consistency**. A client that `POST`s an order and immediately `GET`s it can
  get 404. Mitigations: return the created resource in the POST response (we do), have the client poll or retry,
  read-your-writes via a version token ("wait until read model version ≥ N"), or route the first read to
  the command side. You also pay for more moving parts, duplicate DTOs per service, and projection bugs.
- Data ownership (ADR-0011): each service writes only its own schema, so there are no cross-service joins.
  ArchUnit tests enforce module boundaries inside the monolith.

**Q: How are duplicate API requests handled (idempotency)? Walk through the state machine.**
A: The client sends `Idempotency-Key: <uuid>` on `POST /orders` (optional: without it there is no protection).
`OrderController.executeWithIdempotency`:
1. `requestHash = SHA-256(json(request))`.
2. `IdempotencyService.evaluate(key, hash)` in **`REQUIRES_NEW`**:
   - `INSERT INTO "order".idempotency_keys (key, request_hash, status) VALUES (?, ?, 'IN_PROGRESS')
     ON CONFLICT (key) DO NOTHING`. If 1 row is inserted → **Proceed**. This is atomic across all pods,
     because the primary-key constraint is the lock.
   - Otherwise load the record. **Hash mismatch** → `422 Unprocessable` (same key, different body, which is a
     client bug or an attack). `IN_PROGRESS` → **409 Conflict** (a concurrent duplicate). `COMPLETED` →
     **replay** the stored status and body without re-executing. `FAILED` → delete and proceed.
3. Execute `orderService.createOrder` (its own transaction). On exception → `fail(key)` deletes the row so the
   client can retry. On success → `complete(key, 201, responseJson)`.
4. Rows expire after **24 h** (`expires_at` default, index on `expires_at`), and `IdempotencyPruningService` deletes them in batches.
- Senior edge cases:
  - **Crash between the order commit and `complete()`**: the order exists, but the key stays `IN_PROGRESS`, so
    retries get 409 until expiry (24 h). Fix: write the idempotency completion **in the same transaction as the
    order** (same DB, so that's easy), or give `IN_PROGRESS` a lease (`locked_until`) after which a retry may take over.
  - **Key scope**: keys are global. Scope them by client or tenant (`PRIMARY KEY (client_id, key)`), otherwise
    a second client reusing a key with an identical body gets the *first client's response*.
  - Hashing `objectMapper.writeValueAsString(request)` depends on field order and serialization settings. Canonicalize
    the payload before hashing.
  - Idempotency is also needed at every async hop (see consumers), not only at the API.

**Q: How are cross-service failures handled — explain the saga.**
A: A **choreographed saga**. Each step is a local ACID transaction that emits an event, and failures trigger
**compensating transactions** (semantic undo, not a rollback):
```
POST /orders ─► OrderService (orders + outbox) ─► order-placed
                                                     │
                       inventory OrderPlacedConsumer ◄┘
                         ├─ ok:   reservations (UNIQUE(event_id, sku), idempotent)
                         └─ fail: inventory-reservation-failed ─► OrderCompensationConsumer
                                                                   └─ cancelOrder(): PLACED→CANCELLED (guarded)
                                                                        └─ outbox ─► order-cancelled ─► order-query projection
```
- Retries: transient errors use blocking retries with exponential backoff and jitter (forward recovery). Business
  failures (out of stock) are **not** retried. They produce a failure event (backward recovery).
- Compensation must be **idempotent and commutative-tolerant**: `cancelOrder` returns early if the
  order is already `CANCELLED` and uses a conditional status update. Saga metrics:
  `saga_reservations_failed_total`, `saga_compensations_total`.
- Honest caveat: the inventory step here is a **simulation**. A SKU containing `oos` /
  `out-of-stock`, or quantity > 9999, means out of stock, because there is no real stock-level table yet. In production,
  reservation would be `UPDATE stock SET available = available - :q WHERE sku = :s AND available >= :q`
  (atomic, no oversell), with a reservation TTL.
- Another senior catch: `OrderPlacedConsumer` publishes `InventoryReservationFailed` with a direct,
  **fire-and-forget** `kafkaTemplate.send` and then acknowledges the offset. If that send fails, the compensation
  event is lost and the order stays `PLACED` forever. Fix: use an outbox in the inventory context too, or wait for
  the send future before acknowledging (`send(...).get()`, so an exception triggers retry, then DLQ).

**Q: Choreography vs orchestration?**
A: *Choreography* (ours): services react to each other's events with no central coordinator. It has low
coupling and is great for 2–4 steps, but the workflow is implicit (spread across consumers), hard to visualize,
and risks cyclic dependencies as steps grow. *Orchestration*: a saga orchestrator (a state machine,
e.g. Temporal/Camunda or a hand-rolled table) sends commands and tracks state. The flow is explicit,
with easier timeouts, compensation ordering, and visibility, at the cost of a central component and more coupling to it.
Switch to orchestration when the saga gains payment, shipping, notifications, and timeouts
("cancel if payment isn't confirmed within 15 min").

**Q: CAP and PACELC — where does this system sit?**
A: During a partition you choose consistency or availability. **PACELC** adds: *else* (no
partition), latency vs consistency.
- Order writes: **CP** within Postgres (single primary, **synchronous standby** means a commit waits
  for the standby, trading latency for RPO≈0). If the primary is lost, writes pause during Patroni failover.
- Reads: **AP/EL**. Read replicas (lag ≤ 100 ms), caches, and an eventually consistent read model
  trade freshness for latency and availability.
- Kafka with `acks=all, min.insync=2`: it refuses writes rather than risk loss, and the outbox buffers
  them in Postgres, so the user-facing write path stays available while Kafka is down.

**Q: How do you get "exactly-once" business effects end to end?**
A: Every hop must be idempotent or deduplicated:
API → idempotency key. DB write + event → outbox (atomic). Relay → at-least-once (duplicates possible).
Kafka producer → idempotent producer (no broker-level duplicates from retries). Consumer → unique
constraint, version guard, or inbox table **in the same transaction as the side effect**, followed by an offset
commit. Exactly-once is a *property of the whole pipeline*, never of a single component.

**Q: Rate limiting — what algorithms exist and what do we use?**
A: Algorithms: fixed window (simple, allows 2× bursts at window edges), sliding log (exact, memory-heavy),
sliding-window counter (a good approximation), **token bucket** (allows bounded bursts at a sustained
rate, the most common choice), leaky bucket (smooths output).
- **Edge (HAProxy):** `stick-table type ip size 100k expire 1m store http_req_rate(1m)`. Requests
  are tracked per **source IP**, and more than **500 req/min** returns 429 with `Retry-After: 60` (compose config;
  the Helm value `ingress.rateLimit` is set very high for load tests). Two HAProxy replicas
  **peer-sync their stick tables** so the limit is global across ingress pods
  (`K8sIngressPeerSyncFailure` alerts if they disconnect). HAProxy also *overwrites*
  `X-Forwarded-For` with the real source address, which prevents spoofing.
- **App (`ClientRateLimitFilter`):** a fixed window per client ID (`X-API-Key`, else the first
  `X-Forwarded-For`, else the remote address), with counters in Caffeine (`expireAfterWrite 60s`, max 100k clients).
  It returns `X-RateLimit-Limit`/`Remaining` headers and 429 + `Retry-After`. It is **disabled by default**
  (`APP_RATE_LIMITING_ENABLED=false`) and **per pod**, so the effective limit is `limit × pods`. For a true global
  limit use Redis (atomic Lua token bucket) or rely on the edge.
- Trusting `X-Forwarded-For` is only safe behind a proxy that rewrites it, otherwise clients spoof their identity.

**Q: Load shedding — how does it work and how is it different from rate limiting?**
A: Rate limiting enforces **per-client fairness** ("you've had your share"). Load shedding protects the
**server's capacity** regardless of who is asking ("we're overloaded, drop the least important work").
`AdaptiveLoadShedderFilter`:
- classifies requests. **Protected**: `/actuator*`, any `POST` (order creation is never shed), `/orders*`.
  **Degradable**: `GET /catalog*`,
- tracks in-flight degradable requests in an `AtomicInteger`. When the count reaches
  **`maxInFlightDegradable = 500`** (or an operator sets `forcedOverload`), new degradable requests get
  **429 + `Retry-After: 5`** and `load_shedding_dropped_total{endpoint}` is incremented,
- priority order: revenue (place order) > order lookups > browse/search.
- Senior critique:
  - It's a **static concurrency limit**, not truly adaptive. `latencyThresholdMs = 200` exists in
    `LoadSheddingProperties` but **nothing reads it**. A real adaptive limiter (Netflix
    `concurrency-limits`: AIMD / Vegas / Gradient) measures latency and moves the limit, and Little's law
    (`L = λ·W`) gives the starting value.
  - 503 is arguably more correct than 429 for server overload (429 implies the client misbehaved).
  - The metric tag `endpoint = request.getRequestURI()` is **unbounded cardinality**
    (`/catalog/products/1`, `/2`, …), which can blow up Prometheus memory during exactly the incident you need it
    for. Use the route template (`/catalog/products/{id}`) or a fixed class label.

**Q: What other resilience patterns exist, and which are here?**
A:
- **Timeouts everywhere** (here): Redis 1 s, Hikari connection acquisition 5 s, Kafka producer `max.block.ms` 3 s,
  outbox publish wait 10 s, HAProxy `connect 5s / client 30s / server 30s / http-request 10s`.
- **Retries with exponential backoff and jitter** (here): Kafka consumers. HAProxy `retries 3`. Always cap retries,
  and only retry idempotent operations.
- **Bulkheads** (partially): separate primary and replica pools, separate Kafka consumer groups. Separate
  thread pools per dependency would be the next step.
- **Circuit breakers** (not here): Resilience4j around Redis L2 and any future HTTP dependency, to stop
  paying timeouts against a dead dependency.
- **Fail-open / graceful degradation** (here): caches. **Fail-closed** where correctness matters
  (idempotency and orders go to Postgres or fail).
- **Backpressure** (here): bounded pools, `Retry-After`, Kafka as a buffer, the outbox as a buffer.

---

## 8. Scaling, Resilience & Kubernetes — Q&A

**Q: How is the system deployed on Kubernetes?**
A: Helm chart `performance/helm/hyperscale-commerce`:
- **Deployments** (stateless): `app` and `order-query`, 5 replicas each initially, requests
  `500m / 512Mi`, limits `2 CPU / 1Gi`.
- **StatefulSets** (stable identity and storage): Kafka ×3 (KRaft), etcd ×3, Postgres/Patroni ×3, Redis ×1,
  HAProxy ingress ×2. They use headless services for per-pod DNS (`postgres-ha-0.postgres-ha-headless…`).
- **HPA v2**: `minReplicas 3`, `maxReplicas 8`, CPU 70%, memory 75%.
- **PDB**: `minAvailable: 2` per service. **RollingUpdate** `maxUnavailable: 0, maxSurge: 1`.
  **topologySpreadConstraints** `maxSkew: 1` on `kubernetes.io/hostname` (`ScheduleAnyway`).
- **Probes**: startup (`/actuator/health/liveness`, 10 s initial delay, 3 s × 30), readiness
  (`/actuator/health/readiness`, 5 s), liveness (10 s, `failureThreshold 6`).
- **Security**: Pod Security Standard `restricted`, `runAsNonRoot` (UID 10001), drop all
  capabilities, seccomp `RuntimeDefault`, NetworkPolicies, a dedicated ServiceAccount.

**Q: How does zero-downtime deployment actually work here?**
A: `maxUnavailable: 0, maxSurge: 1`: a new pod starts → the startupProbe gives the JVM time to warm up →
readiness passes → HAProxy and the Service send traffic → only then is an old pod terminated. On termination:
SIGTERM → readiness goes down → Spring graceful shutdown (up to 30 s for in-flight requests, Kafka
consumers commit and leave the group). Make sure `terminationGracePeriodSeconds` (default 30 s) is **≥ the Spring
shutdown phase plus a buffer**, and consider a `preStop` sleep of a few seconds so endpoints are removed from load
balancers before Tomcat stops accepting connections. Without it, you get a burst of connection resets on every deploy.
Schema changes must be backward compatible with the old pods: **expand → migrate → contract** across
releases.

**Q: Is CPU/memory-based HPA a good fit for a JVM service? What would you scale on instead?**
A: CPU works reasonably for request-bound services. **Memory is a poor signal for the JVM**: the heap grows
to its maximum and rarely returns memory to the OS, so 75% memory utilization can keep pods scaled out
forever or trigger scale-outs that don't help. Better options:
- **request concurrency or RPS per pod**, or **p95 latency** (Prometheus Adapter / KEDA),
- for consumers, **Kafka consumer lag**, but only useful up to the partition count (3 here),
- `K8sHpaMaxedOut` alerts when the HPA hits 8 replicas for 5 minutes, which means you're out of headroom.
- Also check the downstream budget before raising `maxReplicas` (next question).

**Q: What's the capacity math for database connections?**
A: Each pod opens up to **30 primary + 20 replica** connections. At `maxReplicas: 8` that's
`8 × 50 = 400` connections per service, and **~800** across `app` + `order-query`. Postgres
`max_connections` must cover that plus superuser, replication, and Patroni slots, and each Postgres backend costs
memory (~5–10 MB). Too many active connections also *slows* Postgres through contention. The HikariCP
guideline is `connections ≈ (cores × 2) + effective_spindles` **per database**, not per pod.
Fixes: smaller per-pod pools, **PgBouncer** in transaction mode in front of Postgres, or both.
Autoscaling the app without a DB connection budget just moves the bottleneck.

**Q: How do you approach capacity planning for "10k concurrent users, p95 < 200 ms, 5× spike"?**
A: Little's law: `in-flight = RPS × latency`. If 10k users each make a request every 5 s, that's
2,000 RPS. At 50 ms average latency, ~100 requests are in flight cluster-wide, which is trivial for 5 pods × 500
threads. A 5× spike means 10k RPS and ~500 in flight, *if latency holds*. The real limit is the
slowest shared resource: DB connections, the Postgres primary's write IOPS, and Kafka partitions. So:
1. measure per-pod max sustainable RPS at the SLO (k6 `baseline.js`, `qualification-10k.js`),
2. find the knee of the latency curve and the saturating resource (JFR, Hikari pending, `pg_stat_activity`),
3. provision `peak / per-pod-capacity` pods with N+1 headroom, keeping the DB connection budget in mind,
4. add shedding so beyond-capacity traffic degrades gracefully (`spike-5x.js` checks this).

**Q: What chaos / failure testing was done?**
A: `performance/chaos/` uses **Toxiproxy** to inject latency, bandwidth limits, and connection resets between the app and
Postgres/Kafka/Redis (`inject-toxic.sh`), poison messages (`inject-poison.sh`), Kafka broker kills
(`chaos-kafka.js`), and Postgres failover (`run-ha-chaos.sh`, `chaos-postgres.js`). The integration tests
`KafkaOutageIntegrationTest`, `PostgresOutageIntegrationTest`, `PartialOutageIntegrationTest` assert
behavior in code. Example expectation: with Kafka down, `POST /orders` still succeeds, the outbox backlog grows
(`outbox_relay_lag` ↑), and when Kafka returns the backlog drains with **no lost events**. Methodology:
form a hypothesis about steady state → inject one fault → observe SLIs → verify recovery → document it in
`docs/bootcamp/evidence/`.

**Q: How do you scale writes beyond a single Postgres primary?**
A: In order: (1) make the primary efficient (indexes, batching, smaller transactions, removing hot
rows, partitioning big tables like the outbox by time), (2) move reads off it (replicas and caches, done),
(3) **vertical scale**, (4) split by bounded context into separate databases (schemas are already separate,
so this is mostly operational), (5) **shard** by `order_id` or customer (Citus, or app-level
routing), accepting the loss of cross-shard transactions and joins (sagas already handle cross-context
consistency). Sharding is the last resort because of resharding pain and operational complexity.

---
## 9. Monitoring & Observability — Q&A

**Q: Monitoring vs observability? What are the pillars?**
A: Monitoring answers known questions ("is p95 over 200 ms?") with dashboards and alerts. Observability is
the ability to answer *new* questions about system behavior from its outputs without shipping new
code. Pillars: **metrics** (cheap, aggregated, for alerting), **logs** (high-detail events), **traces**
(causal request paths across services). Increasingly also **profiles** (continuous JFR / pprof).
Frameworks to name: **RED** (Rate, Errors, Duration) for services, **USE** (Utilization,
Saturation, Errors) for resources, and the **four golden signals** (latency, traffic, errors, saturation).

**Q: How are metrics collected here, and what are the key ones?**
A: Micrometer → `/actuator/prometheus` (exposure is limited to `health, info, prometheus`) → Prometheus
scrape → Grafana (`performance/monitoring/dashboards/platform-overview.json`) and alert rules
(`alerts.yml`, `cache-replica-alerts.yml`, `k8s-alerts.yml`).
| Area | Metric (Prometheus name) |
|---|---|
| HTTP RED | `http_server_requests_seconds_{bucket,count,sum}{uri,method,status}` |
| Outbox | `events_published_total{topic}`, `outbox_relay_lag` (age of oldest unpublished, s) |
| Consumers | `events_consumed_total{event_type,outcome,consumer}`, `events_out_of_order_total`, `events_dlq_total`, `kafka_consumer_lag` |
| Saga | `saga_reservations_failed_total`, `saga_compensations_total` |
| Read model | `order_read_model_lag_seconds` |
| Cache | `hyperscale_cache_gets_total{cache,level,result}`, `hyperscale_cache_evictions_total{cache,reason}` |
| Protection | `load_shedding_dropped_total{endpoint}`, `http_rate_limited_requests_total` |
| DB | `hikaricp_connections_*`, `datasource_connections_active{pool}`, `postgres_replication_lag_seconds`, Patroni / `pg_stat_replication` exporters |
| Lifecycle | `storage_pruned_rows_total{table}`, `dlq_replayed_events_total{outcome}` |

**Q: What SLOs are tracked, and how are they alerted?**
A: From `alerts.yml`:
- **Latency SLOs (p95 < 200 ms for 1 m)**: `CatalogApiP95LatencyBreached`,
  `OrderCreationApiP95LatencyBreached` (POST `/orders`), `OrderQueryApiP95LatencyBreached` (GET `/orders*`), computed
  correctly as `histogram_quantile(0.95, sum(rate(..._bucket[2m])) by (le))`.
- **Errors**: `HttpServerErrorRateHigh`, 5xx ratio > 0.1% for 2 m (matching a 99.9% target).
- **Async pipeline**: `DeadLetterQueueArrival` (any DLQ increase), `OutboxRelayBacklogAging` (> 5 s),
  `KafkaConsumerLagHigh` (> 100 for 2 m).
- **DB**: `HikariCpConnectionTimeout`, `HikariCpConnectionLeakDetected`, `PostgreSQLNoLeader` (15 s),
  `PostgreSQLSplitBrain` (> 1 leader), `PostgreSQLSyncStandbyMissing`, `PostgreSQLEtcdQuorumDegraded`,
  `PostgreSQLReplicationLagHigh` (> 50 MB), `PostgreSQLWalArchiveFailure`.
- **Cache/replica**: `RedisNodeDown`, `L2CacheMissRateSpike`, `PostgresReplicationLagHigh` (> 1 s),
  `CacheInvalidationLagHigh`.
- **Kubernetes**: crash-looping, not ready, OOMKilled, node not ready, PDB at risk, HPA maxed out, ingress peer sync.
- Senior upgrade: static thresholds over 1 m windows are noisy. Use **error-budget burn-rate
  alerts** (Google SRE multi-window, e.g. page if 2% of the monthly budget burns in 1 h *and* the 5 m window
  confirms, open a ticket for slow burns). Page on symptoms (SLO burn), not causes (CPU), and link every alert to
  a runbook (`docs/observability/runbooks/`).

**Q: Why can't you average p95s across pods?**
A: Percentiles are not additive. The mean of per-pod p95s is not the cluster p95, and one hot pod
disappears into the average. Aggregate **histogram buckets** first (`sum by (le)`), then compute the
quantile. That's why Micrometer histograms (`_bucket`) matter more than client-side precomputed
percentiles, which can't be aggregated. Also watch for **coordinated omission** in load tests (k6 handles it with arrival-rate
executors).

**Q: Found any monitoring bugs? (great interview story)**
A: Yes, a metric name mismatch. The app registers the outbox backlog gauge as **`outbox_relay_lag`**
(`OutboxConfig`, and the Kafka runbook uses that name), but the `OutboxRelayBacklogAging` alert queries
**`outbox_unpublished_oldest_age_seconds`** and the dashboard panel queries **`outbox_unpublished_records`**.
Neither series exists, so **the alert can never fire** (a missing series isn't an error in PromQL, so
the alert just evaluates to empty). Lessons: (1) unit-test alert rules with `promtool test rules` against
real metric names, (2) add `absent(metric)` meta-alerts for critical series, (3) treat metric names
as a contract (constants shared between code and rules). Similarly, `HikariCpConnectionLeakDetected`
depends on leak detection being enabled, but the hand-built Hikari pools don't set `leakDetectionThreshold`.

**Q: Is distributed tracing implemented?**
A: **Partially, by design**: trace *context* is created and propagated, but spans are **not exported**.
- `TracingConfiguration` builds Micrometer Tracing on **Brave** with `SpanHandler.NOOP`, so spans exist
  in-process (trace and span IDs are generated) but are never sent to Zipkin, Tempo, or Jaeger.
- Propagation: HTTP via the Micrometer observation and `CorrelationIdFilter` (returns `X-Trace-Id`,
  `X-Correlation-Id`, `X-Instance-Id`). Kafka via an observation-enabled `KafkaTemplate`, the
  `KafkaCorrelationProducerInterceptor` (`correlation-id` header), and `CorrelationIdRecordInterceptor` on
  consume. **Across the outbox** (the hard part, since the publish happens later on a different thread), trace fields are
  stored *in the event payload* (`traceId`, `parentSpanId`, `sampled`, `correlationId`), and `OutboxRelay`
  starts an `outbox.publish` span re-parented on the original request's span.
- So today you correlate through **logs** (`traceId` / `correlationId` in JSON MDC fields). To finish the job, add an
  OTLP or Zipkin reporter, a sampling strategy (head-based percentage, or tail-based in the collector to keep
  errors and slow traces), and exemplars linking metrics to traces.

**Q: How is logging structured, and what are the best practices?**
A: `logback-spring.xml` has two profiles. `local` uses a human-readable console. `!local` uses
**`LogstashEncoder` JSON** with a `{"service": "<app name>"}` custom field, and MDC keys (`correlationId`, `traceId`,
`spanId`, `instanceId`) are automatically included as JSON fields. Best practices: structured key/value
fields (not string concatenation), log at boundaries and decisions (not every line), no PII or secrets in logs
or MDC (ADR-0027 §3.3), parameterized messages (`log.info("x={}", x)`, which avoids building strings when the level
is disabled), log once per failure (not at every layer), and sample high-volume debug logs. In K8s, write to
stdout and let the platform ship logs (Fluent Bit → Loki/ELK).

**Q: Walk me through debugging an outbox backlog.**
A:
1. **Signal**: `outbox_relay_lag` rising, and/or `KafkaConsumerLagHigh`, plus user-visible symptoms (orders not appearing
   in `GET /orders`, `order_read_model_lag_seconds` rising).
2. **Scope it**: is it all pods or one? Is `events_published_total` still increasing (slow) or flat (stuck)?
3. **Check the DB side**:
   `SELECT count(*), min(created_at) FROM "order".outbox_events WHERE published_at IS NULL;`
   Look for long transactions or locks blocking the claim query in `pg_stat_activity` / `pg_locks`.
4. **Check the Kafka side**: Kafka health indicator (`/actuator/health`, `partitionsFor("health-check")`),
   relay logs (`Failed to publish outbox event … to topic …`), ISR status. If ISR < `min.insync.replicas`,
   producers get `NotEnoughReplicas`.
5. **Check the relay thread**: a thread dump (is the scheduler thread stuck in `get(10s)` timeouts?), and whether other
   `@Scheduled` jobs are hogging the single scheduler thread.
6. **Remediate**: fix the broker or ISR, then watch the drain rate (relay loops without sleeping while batches are full).
   If the backlog is huge, temporarily raise `claim-limit` or scale pods (mind duplicate publishing). Afterwards,
   check `outbox_events` bloat and VACUUM.
7. **Follow-up**: blameless postmortem, add the missing alert (the metric name bug above), add a runbook entry.

**Q: Walk me through debugging a p95 latency regression on `GET /orders/{id}`.**
A: Check whether it is all pods or one pod (a node issue or noisy neighbor), and whether it started with a deploy (compare
`hyperscale.com/git-revision`). Look at the cache: did the L1/L2 hit ratio drop (Redis down, a mass invalidation, a TTL change)?
Then the DB: replica lag above 100 ms flips reads to the primary (more load there), and Hikari pending connections or
timeouts point at the pool. Then the JVM: GC pauses (GC logs, JFR), thread pool saturation (Tomcat busy threads), CPU
throttling (`container_cpu_cfs_throttled_seconds_total`, since a 2-CPU limit with bursty GC threads throttles). Then the
query: `EXPLAIN (ANALYZE, BUFFERS)`. Fix the cause, then add a guardrail (alert or test) so it can't silently regress.

---

## 10. Database (PostgreSQL) — Q&A

**Q: Explain ACID.**
A: **Atomicity**: all or nothing (the outbox relies on it: order + event commit together). **Consistency**:
constraints and invariants hold before and after (`CHECK (price >= 0)`, `CHECK (quantity > 0)`, FKs, UNIQUE).
**Isolation**: concurrent transactions behave as if serialized, to the degree the isolation level allows. **Durability**:
committed data survives crashes (WAL fsync, plus **synchronous replication** here, so it survives losing the primary
node too).

**Q: Isolation levels, anomalies, and Postgres specifics?**
A:
| Level | Dirty read | Non-repeatable | Phantom | Write skew | Postgres notes |
|---|---|---|---|---|---|
| READ UNCOMMITTED | possible (SQL std) | ✓ | ✓ | ✓ | behaves as READ COMMITTED |
| READ COMMITTED (default) | ✗ | possible | possible | possible | new snapshot per *statement* |
| REPEATABLE READ | ✗ | ✗ | ✗ in PG | possible | snapshot isolation. Concurrent update → error `40001` |
| SERIALIZABLE | ✗ | ✗ | ✗ | ✗ | SSI. Aborts with `40001` and the app must retry |
- *Write skew* example: two doctors each check "≥ 2 on call" and both go off call. Each transaction is valid on its own,
  but together they break the invariant. Fixes: SERIALIZABLE, `SELECT ... FOR UPDATE` on the rows that matter, or a
  constraint.
- We stay on **READ COMMITTED** and push correctness into **atomic statements and constraints**:
  `INSERT ... ON CONFLICT DO NOTHING` (idempotency), `UNIQUE(event_id, sku)` (dedup), conditional
  `UPDATE ... WHERE version <= ?` (read model), `SKIP LOCKED` (work distribution). This is the senior
  default: cheap, explicit, and no retry storms.

**Q: How does MVCC work in Postgres, and why does it matter for the outbox?**
A: Each row version (tuple) carries `xmin`/`xmax` transaction IDs, and readers see the versions visible to their
snapshot, so **readers never block writers**. An UPDATE writes a *new* tuple and marks the old one dead. **VACUUM**
reclaims dead tuples and freezes old XIDs (it prevents transaction ID wraparound). HOT updates avoid index
updates when no indexed column changes and the page has space.
- The outbox is the worst case for MVCC churn: INSERT, then UPDATE `published_at` (a dead tuple, and since the
  partial index predicate changes, the update is not HOT), then DELETE after 7 days. Expect **table and index bloat**. Mitigations:
  tune autovacuum for that table (`autovacuum_vacuum_scale_factor` small), keep the partial index, or
  **partition by time and `DROP` old partitions** (no row-by-row delete, no bloat). Long-running transactions
  (including idle-in-transaction connections) block vacuum everywhere, so monitor
  `pg_stat_activity.xact_start`.

**Q: Pessimistic vs optimistic locking — when do you use each?**
A: *Pessimistic* (`SELECT ... FOR UPDATE`, `FOR NO KEY UPDATE`, `NOWAIT`, `SKIP LOCKED`): lock first, then act.
Use it under high contention or when conflicts are expensive. *Optimistic*: read a version, then
`UPDATE ... WHERE id = ? AND version = ?`. If 0 rows are updated, someone else won, so retry or report a conflict. Use it under low contention
and for long user think-time. The read model uses a variant, a **monotonic version guard**
(`WHERE version <= incoming`), so older events can never overwrite newer state. That gives idempotence and
out-of-order safety in one predicate.

**Q: How do indexes work, and what are the trade-offs? Critique the repo's indexes.**
A: The default is a **B-tree**: balanced and ordered, `O(log n)` lookups, and it supports range and prefix queries and ORDER BY. Other types:
**Hash** (equality only), **GIN** (JSONB, arrays, full-text, trigram), **GiST** (geo, ranges), **BRIN** (huge
append-only tables with physical correlation, e.g. timestamps). Variants: **composite** (column order matters, so
leftmost prefix and equality columns go before range columns), **partial** (`WHERE published_at IS NULL`), **covering**
(`INCLUDE (...)` for index-only scans), **expression** (`lower(email)`).
Trade-offs: slower writes (every index is updated, and non-HOT updates touch all of them), storage, bloat, and planner cost. Low-selectivity
columns (boolean, status) rarely help unless the index is partial.
Repo review:
- `catalog.products`: `sku UNIQUE` already creates a unique B-tree, so the extra **`idx_products_sku` is
  redundant** (double write cost, no benefit).
- Product search uses `likeIgnoreCase('%q%')` on `name` and `sku`. A leading-wildcard `ILIKE` **can't use
  `idx_products_name`**, so it's a sequential scan. Fix: `pg_trgm` + `GIN (name gin_trgm_ops)`, or full-text
  search (`tsvector`), or a search engine when relevance matters.
- `"order".order_items.order_id` has an FK but **no index**. Postgres doesn't index FKs automatically,
  so loading items per order and deleting orders scan the whole table.
- `order_query.order_read_model`: `listOrders` does `ORDER BY created_at DESC, order_id DESC LIMIT/OFFSET`
  plus `COUNT(*)` on every page, with no supporting index. Add `(created_at DESC, order_id DESC)` and switch to **keyset
  pagination** (`WHERE (created_at, order_id) < (:c, :id)`). OFFSET is `O(offset)`, and exact counts on
  large tables are expensive (use estimates or omit them).
- Good: the partial outbox index, the `idempotency_keys.expires_at` index for pruning, `UNIQUE(event_id, sku)`.

**Q: How do you investigate a slow query?**
A: `EXPLAIN (ANALYZE, BUFFERS)`. Check estimated vs actual rows (bad estimates mean stale stats, so run `ANALYZE`, or
correlated columns, so create extended statistics), Seq Scan vs Index Scan, sort spilling to disk (`work_mem`), nested loops
over large inputs, and buffer hits vs reads. Use `pg_stat_statements` to find the top queries by total time (frequency ×
latency matters more than the single slowest query). Use `auto_explain` for queries over a threshold. Fix in this order: query shape → index →
schema → configuration.

**Q: What is the N+1 problem, and how is it avoided here?**
A: Loading a list and then issuing one query per element for its children: 1 + N round trips. ORMs with lazy
loading are the usual cause. Fixes: join fetch, batch `IN (...)` queries, or denormalize for reads. Here, the read
model stores items as **JSONB inside the order row**, so one row = one query for `GET /orders/{id}`. That is denormalization
on the query side, which is what CQRS is for. Spring Data JDBC loads an aggregate and its children
deterministically, without lazy proxies.

**Q: How is connection pool exhaustion handled? How do you size HikariCP?**
A: Symptoms: `SQLTransientConnectionException: Connection is not available, request timed out after
5000ms`, rising `hikaricp_connections_pending`, request latency stuck at the timeout value. Causes: slow
queries, long transactions (including **external I/O inside `@Transactional`**, the most common one), connection
leaks, `REQUIRES_NEW` doubling connections per request, or a pool that's too small for real concurrency.
Diagnose with `pg_stat_activity` (state, `xact_start`, wait events), `pg_locks`, and thread dumps (who is holding connections?).
Remediate: terminate rogue sessions (`pg_terminate_backend`), fix the code path, and set
`idle_in_transaction_session_timeout` / `statement_timeout`.
Sizing: smaller than intuition says. About `cores × 2` active connections per DB server is near optimal, and
more just adds contention. Here: primary 30 / replica 20 per pod, `minimumIdle 5`, `connectionTimeout 5s`,
`idleTimeout 10m`, `maxLifetime 30m` (keep it below any DB or firewall idle cutoff). See section 8 for the
multi-pod connection budget.

**Q: How does read/write splitting work, and what consistency issues does it introduce?**
A: `TransactionRoutingDataSource` (an `AbstractRoutingDataSource`) routes `readOnly` transactions to the
**replica pool** only if `ReplicationLagTracker.isLagAcceptable()`. `ReplicaLagMonitor` samples every 1 s:
```sql
SELECT CASE WHEN NOT pg_is_in_recovery() THEN NULL                        -- not a replica → unhealthy
            WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0   -- fully caught up
            ELSE EXTRACT(EPOCH FROM (clock_timestamp() - pg_last_xact_replay_timestamp())) * 1000 END
```
If lag > `max-lag-ms` (100) or the replica is unreachable, reads fall back to the primary (fail-safe). The JDBC URLs
use **multi-host failover**: `targetServerType=primary` for writes, and `targetServerType=secondary&loadBalanceHosts=true`
for reads, so after a Patroni failover the driver finds the new primary itself.
- Issues: **read-your-writes** (a user writes and then reads from a replica 80 ms behind, which is within budget, and doesn't see
  their write). Mitigations: route a session to the primary for N seconds after a write, use LSN-based waits
  (`pg_last_wal_replay_lsn() >= :commitLsn`), or keep critical reads on the primary. **Monotonic reads** (two
  requests hit replicas at different lag) are a reason to pin sessions. The lag sample is up to 1 s old, so the
  100 ms threshold is approximate.

**Q: How is the database made highly available?**
A: **Patroni** manages 3 Postgres 16 nodes. **etcd** (3 nodes, quorum 2) is the DCS, holding the leader key
with a TTL. Only the holder of the leader lock may run as primary, and a node that loses the lock demotes itself
(**fencing**, so no split brain). Replication: a **synchronous standby** (a commit waits for the standby to receive the WAL, so
RPO ≈ 0 for acknowledged commits) plus asynchronous replicas for reads. Backups: **pgBackRest** full and
incremental backups plus continuous **WAL archiving**, which enables **point-in-time recovery** (`PostgreSQLWalArchiveFailure`
alert). Failover: the leader key expires → the most up-to-date sync standby is promoted → apps reconnect via the
multi-host JDBC URL. In-flight transactions fail and must be retried by clients (idempotency keys make that
safe). Alerts cover no leader, split brain, missing sync standby, and etcd quorum.
- Trade-off of synchronous replication: write latency includes a network round trip to the standby, and if *no*
  sync standby is available, commits block (or Patroni relaxes to async if `synchronous_mode_strict` is off). That's
  availability vs durability, and you must choose consciously.
- RTO vs RPO: RPO is how much data you can lose (≈0 here). RTO is how long you're down (the leader TTL plus
  promotion plus client reconnect, typically tens of seconds).

**Q: How do you run schema migrations with zero downtime?**
A: Flyway versioned migrations run on startup against the primary (`targetServerType=primary`). Each service has
its own location and schema (`db/migration` vs `db/migration-order-query`, schema `order_query`).
Rules: **expand/contract** (add a nullable column → deploy code that writes both → backfill in batches
→ switch reads → drop the old column in a later release), never rename in place, create big indexes with
`CREATE INDEX CONCURRENTLY` (which can't run inside a transaction, so Flyway needs non-transactional mode for it),
set `lock_timeout` so DDL can't queue behind long transactions and block everyone, and avoid table-rewriting
operations (adding a column with a volatile default, changing a type) on hot tables. Example from the repo:
`V2__add_version_to_order_read_model.sql` adds `version BIGINT NOT NULL DEFAULT 1`. A constant default is a
metadata-only change on PG 11+, so it's safe on a large table.

**Q: Schema-per-context in one Postgres — why, and what are the rules?**
A: (ADR-0011) Separate schemas (`catalog`, `"order"`, `inventory`, `order_query`) give logical ownership
without operational cost. Rules: a context writes only its own schema, there are no cross-schema joins or FKs between contexts,
and integration happens through events. That makes a later physical split (separate databases) a deployment change, not a
rewrite. Risk: nothing at the DB level *stops* a cross-schema query. Enforce it with per-service DB roles that only
have grants on their schema, plus ArchUnit in code.

**Q: JSONB — when is it appropriate?**
A: For document-shaped data read as a whole and rarely queried inside: the outbox `payload`, read-model `items`.
Benefits: schema flexibility, no join. Costs: weaker constraints, larger rows (TOAST beyond ~2 KB),
whole-value rewrite on update, and you need GIN indexes to query inside. Don't use it for relational data you
filter or join on.

---
## 11. Testing Strategy — Q&A

**Q: Describe the testing strategy.**
A: A test pyramid plus explicit non-functional qualification:
- **Unit tests**: domain and application logic with fakes (`CatalogDomainTest`, `CatalogServiceTest`,
  `NearCacheTest`, `TransactionRoutingDataSourceTest`, `ClientRateLimitFilterTest`, `KafkaRetryPolicyTest`).
- **Architecture tests (ArchUnit)**: `CatalogArchitectureTest`, `OrderArchitectureTest`,
  `InventoryArchitectureTest`, `SharedArchitectureTest`, `EngineeringPolicyArchitectureTest`,
  `OrderQueryArchitectureTest`. They enforce bounded-context isolation and layering (domain doesn't depend on
  infrastructure, no cross-context imports) and engineering policies. Architecture rules become **failing
  builds** instead of wiki pages.
- **Concurrency tests**: `ContextSafetyConcurrencyTest` (MDC and trace context don't leak between
  tasks), `DataSourceBackpressureConcurrencyTest` (pool limits hold under concurrency),
  `VirtualThreadsQualificationTest` (transaction, MDC, and Brave behavior across virtual-thread boundaries).
- **Integration tests (Testcontainers)**: real Postgres, Kafka, and Redis in a separate `integrationTest`
  source set. Resilience cases: `KafkaOutageIntegrationTest`, `PostgresOutageIntegrationTest`,
  `PartialOutageIntegrationTest`.
- **Performance (k6)**: `smoke`, `baseline`, `cached-throughput`, `spike-5x`, `qualification-10k`,
  `saga-idempotency`, `stream-resilience`, `ha-qualification`, `db-ha-qualification`, `chaos-*`.
- **Chaos**: Toxiproxy and broker or database kills (section 8).

**Q: Why Testcontainers instead of H2 / embedded Kafka?**
A: H2 doesn't implement Postgres semantics: no `SKIP LOCKED` behavior, no `ON CONFLICT ... WHERE`,
no JSONB, different locking and isolation. Tests pass and production breaks. Testcontainers runs the **same
engine versions as production**, so the tests actually exercise the outbox claim, the upserts, and the idempotency
races. Costs: slower tests, Docker in CI. Mitigations: reuse containers across a test class or suite
(`IsolatedRedisContainer` support classes), and keep most logic in fast unit tests.

**Q: How do you test asynchronous, event-driven flows?**
A: Never `Thread.sleep(…)`. **Poll with a deadline** (Awaitility: `await().atMost(10s).until(() ->
readModel.find(id).isPresent())`). Assert on *observable effects* (DB rows, metrics, published
records), not on internal calls. Test the unhappy paths explicitly: duplicate delivery (send the same event
twice and assert one reservation), out-of-order delivery (Cancelled before Placed, then assert the final state),
poison messages (assert DLQ routing with no retries for `JacksonException`), and broker outage (assert the outbox
backlog grows and then drains).

**Q: How do you test concurrency bugs like the idempotency race?**
A: Fire N threads at the same key, released together by a `CountDownLatch` start gate, then assert
exactly one `Proceed` and N-1 conflicts or replays, and exactly one order row. Repeat many iterations, because races are
probabilistic. Run against **real Postgres**, since the correctness lives in the unique constraint. For pure
JVM code there is jcstress (JMM-level) or deterministic schedulers. k6 `saga-idempotency.js` does the
same at system level under load.

**Q: Contract testing between services?**
A: Today the `contracts` module shares Java records between producer and consumers. That gives compile-time
safety inside the monorepo but no protection against *wire-format* drift or independently deployed
versions. Next steps: consumer-driven contract tests (Pact for messages), or a schema registry with
compatibility checks in CI (section 6), plus golden-file tests that deserialize v1 payloads with the
current code.

**Q: How do you keep tests from being flaky?**
A: No sleeps or wall-clock dependence (inject `Clock`), isolate state per test (unique keys and topics, clean
schemas), deterministic seeds, explicit timeouts, no reliance on test order, quarantine and fix
flaky tests quickly (a flaky test that gets ignored destroys trust in the suite), and record failures with logs
and container output.

**Q: How do you load test properly?**
A: Define the workload model first (`performance/workloads.md`: endpoint mix, think time, data volume). Use
**open-model / arrival-rate executors** (constant RPS) for latency SLOs, because a closed model hides queueing
(coordinated omission). Warm up before measuring. Run against production-like topology and data size. Record
p50/p95/p99, error rate, and resource saturation together. Make thresholds part of the script so CI fails on
regression. Change one variable at a time, and keep the evidence (`docs/bootcamp/evidence/`).

---

## 12. Security — Q&A

**Q: What security controls exist in this system?**
A:
- **Edge (HAProxy)**: `/admin/*` (including DLQ replay) → **403**. Sensitive actuator endpoints
  (`/actuator/env`, `beans`, `heapdump`, `configprops`) → **404**. Per-IP rate limiting. `X-Forwarded-For` rewritten
  from the real source.
- **App**: Actuator exposure limited to `health, info, prometheus`. Bean Validation (`@Valid`) on requests.
  Parameterized SQL everywhere (jOOQ / `JdbcTemplate` bind parameters, so no SQL injection). Error handlers that don't
  leak stack traces.
- **Platform**: Pod Security Standard `restricted`, non-root UID 10001, `allowPrivilegeEscalation: false`, drop
  all capabilities, seccomp `RuntimeDefault`, NetworkPolicies (default-deny style isolation between tiers), a
  dedicated ServiceAccount, Redis password from a Kubernetes Secret (`secretKeyRef`).

**Q: What would you harden next?**
A: (Shows you can critique your own system.)
1. **Authentication and authorization**: there is no Spring Security yet. Add OAuth2 resource-server
   JWT validation, per-endpoint scopes, and **app-level auth on admin endpoints** (defense in depth, so we don't
   rely only on HAProxy path rules, which are easy to bypass with path tricks or internal callers).
2. **Secrets**: defaults like `redis_secure_password` and Postgres credentials live in `values.yaml` /
   `application.yml` fallbacks and plain env vars. Use External Secrets / Sealed Secrets / Vault, no
   defaults in code, and rotation.
3. **Idempotency key scoping** per principal, to prevent cross-client response replay (section 7).
4. **Transport**: TLS at the edge and mTLS or encryption between services, Kafka SASL/TLS + ACLs, Redis TLS.
5. **Supply chain**: dependency scanning (OWASP dependency-check / Dependabot), image scanning, SBOM, pinned
   base images, non-root distroless images.
6. **Abuse**: request body size limits, pagination caps (`MAX_SIZE = 100` already), and authenticated
   rate-limit identity (API key / user ID) instead of IP only, since NAT and mobile networks put many users behind one IP.
7. **Data**: PII minimization in events and logs, encryption at rest for backups (pgBackRest supports it).

**Q: OWASP API Top 10 items most relevant here?**
A: Broken object-level authorization (`GET /orders/{id}` must check that the caller owns the order, which it
doesn't yet because there's no auth), broken authentication, unrestricted resource consumption (rate limits and shedding
cover part of it), security misconfiguration (actuator exposure, default passwords), and unsafe consumption of
APIs and events (validate event payloads, and treat the DLQ as untrusted input).

---

## 13. System Design Scenarios

**Q: Walk through the life of `POST /orders` end to end.**
A:
1. Client → HAProxy (IP rate-limit check, `X-Forwarded-For` set) → an `app` pod (round robin, readiness-gated).
2. Filters: load shedder (POST is protected), rate limiter (if enabled), correlation filter (MDC +
   response headers). The observation creates the server span.
3. `OrderController`: validates the body. If an `Idempotency-Key` is present: hash the body, then
   `evaluate` in its own transaction (`INSERT ... ON CONFLICT DO NOTHING`).
4. `OrderService.createOrder` (primary connection): insert the order and items, insert the outbox row with the
   `OrderPlacedEvent` JSON, including trace and correlation IDs, then **commit**.
5. `complete(key, 201, body)` → 201 response with the order.
6. Asynchronously, within ~100 ms: `OutboxRelay` claims the row, publishes to `order-placed` keyed by order ID
   (`acks=all`), and marks it published.
7. In parallel: inventory consumer → reservation (deduplicated by `event_id`) or failure event → compensation →
   `OrderCancelled` via the outbox. The order-query projection upserts the read model (version-guarded), evicts
   L1/L2, and broadcasts cache invalidation to all pods.
8. `GET /orders/{id}` on order-query: L1 → L2 → replica (if lag OK) or primary → response.
Failure points to discuss: DB down (5xx, the client retries with the same key, which is safe), Kafka down (201 still returned,
backlog in the outbox), consumer crash (redelivery, idempotent), poison event (DLQ + alert), Redis down (fail-open).

**Q: Design a flash sale (100× traffic on one product for 10 minutes).**
A: The concerns are **hot-key reads, contended stock decrements, and fairness**.
- Reads: serve product data from L1 (single-flight, so the DB sees one load per pod), pre-warm caches before the
  sale, and serve static content from a CDN.
- Writes: never `SELECT stock` then `UPDATE`. Use an atomic conditional decrement
  (`UPDATE stock SET qty = qty - 1 WHERE sku = ? AND qty > 0`). That's still one hot row, which caps throughput.
  At higher scale: **pre-shard stock into N buckets** (rows or Redis keys) and decrement a random bucket, or
  use an atomic Redis Lua decrement as a *gate* with asynchronous order creation through Kafka (the user gets "you're in, confirming…").
- Admission control: a virtual waiting room or token issuance, per-user limits, idempotency keys
  (double-click), and load shedding of browse traffic to keep checkout healthy (the repo's priority model).
- Reconciliation: the outbox and saga guarantee every accepted order is processed, and compensations release
  stock for failed payments. Expire reservations with a TTL.

**Q: How would you add a payment step to the saga?**
A: Add `PaymentRequested` / `PaymentSucceeded` / `PaymentFailed`. Order states:
`PENDING → INVENTORY_RESERVED → PAID → CONFIRMED`, with compensations (release inventory, cancel the order).
Call the payment provider with **an idempotency key per order** (providers support this) and treat timeouts as
*unknown*, not failed: query the status before compensating. Add saga timeouts (cancel if unpaid after X min), which
pushes toward an **orchestrator** with a persisted state machine. Use the outbox in every service that emits events.

**Q: How would you rebuild the read model from scratch (bug fix or new projection)?**
A: Deploy a projection under a **new consumer group** writing to a **new table** (`order_read_model_v2`), replay
from the earliest offset (if retention allows, otherwise backfill from the command DB and then switch to the
stream), let it catch up (lag → 0), switch reads (feature flag), keep the old table for rollback, then drop it.
Projections must be idempotent and version-guarded, as they are here, because replays deliver everything again. If
Kafka retention is shorter than history, you need compacted topics, snapshots, or a backfill job.

**Q: How would you go multi-region?**
A: Start **active-passive**: async replicate Postgres (pgBackRest / streaming) and Kafka (MirrorMaker 2)
to a standby region, with DNS failover, accepting RPO > 0 for cross-region. **Active-active** needs data partitioning
by home region (orders owned by the user's region), conflict handling (CRDTs or last-writer-wins only where
acceptable), region-local caches with region-scoped invalidation, and idempotency keys that work globally.
Latency budgets change: synchronous cross-region commits can't fit inside a 200 ms p95.

---

## 14. Known Gaps & Trade-offs in This Repo (be ready to defend or improve)

Bringing these up yourself shows ownership and seniority. For each one: what the gap is, why it's
tolerable today, and the fix.

| # | Gap | Impact | Fix |
|---|---|---|---|
| 1 | `OutboxRelay.claimDue` runs outside a transaction, so `FOR UPDATE SKIP LOCKED` locks drop immediately | Duplicate publishes across replicas (safe only because consumers are idempotent) | Lease column (`claimed_until`) or a transaction around claim/mark |
| 2 | `OrderCancelled` uses a different topic than `OrderPlaced` | No per-order ordering across lifecycle events | One topic per aggregate (`order-events`), or keep the consumer version guards (current) |
| 3 | `InventoryReservationFailed` sent fire-and-forget, then offset acked | Lost compensation leaves order stuck `PLACED` | Outbox in inventory, or await the send before ack |
| 4 | Idempotency `complete()` in a separate transaction from the order | Crash leaves key `IN_PROGRESS` (409s for 24 h) | Same transaction as the order, or a lease on `IN_PROGRESS` |
| 5 | Idempotency keys not scoped per client | Cross-client response replay | Composite key `(client_id, key)` |
| 6 | Load shedder `latencyThresholdMs` unused | "Adaptive" is really a static in-flight cap (500) | AIMD/gradient limiter using latency |
| 7 | `load_shedding_dropped_total{endpoint=<raw URI>}` | Unbounded metric cardinality | Use route templates |
| 8 | Alert/dashboard query `outbox_unpublished_*` but app exports `outbox_relay_lag` | Outbox backlog alert can never fire | Align names, `promtool test rules`, `absent()` alerts |
| 9 | `spring.datasource.hikari.*` ignored (custom pools) | Leak detection etc. silently off | Configure in `DataSourceRoutingConfig` / bind properties |
| 10 | `RedisL2CacheStore.deleteByPrefix` uses `KEYS` | Blocks Redis at scale | `SCAN` + `UNLINK`, or versioned namespaces |
| 11 | No circuit breaker on L2 | Every miss pays up to 1 s while Redis is down | Resilience4j breaker / short read timeout |
| 12 | Negative lookups not cached | Cache penetration on 404s | Sentinel with short TTL / Bloom filter |
| 13 | Single-threaded default `@Scheduled` pool shared by relay, lag monitor, pruning | Lag sampling stalls during backlog drain | Dedicated scheduler per concern |
| 14 | Spans not exported (`SpanHandler.NOOP`) | No trace UI, logs-only correlation | OTLP/Zipkin exporter + sampling |
| 15 | Offset pagination + `COUNT(*)` on read model, no `created_at` index | Slow deep pages | Keyset pagination + composite index |
| 16 | Redundant `idx_products_sku`, `%q%` search can't use the B-tree, FK `order_items.order_id` unindexed | Wasted writes / seq scans | Drop redundant index, `pg_trgm` GIN, FK index |
| 17 | No authentication; admin endpoints protected only at HAProxy | Security depends on edge config | Spring Security (JWT) + RBAC on admin |
| 18 | DB connection budget at max HPA (~800 connections) | Can exhaust Postgres `max_connections` | Smaller pools / PgBouncer / HPA ceilings tied to DB budget |
| 19 | Inventory is simulated (SKU contains `oos`) | Saga logic proven, stock logic not | Real stock table with atomic conditional decrement |

---

## 15. Likely Deep-Dive / Follow-up Questions

Short "answer sketch" after each one. Expand using the sections above.

- **Why `FOR UPDATE SKIP LOCKED` over advisory locks or a single leader relay?** Work-stealing parallelism without coordination. Advisory locks are session-scoped and leader-style. A leader caps throughput. Mention gap #1.
- **Trade-offs of choreographed vs orchestrated saga?** Low coupling and implicit flow vs explicit state machine and central component. Switch to orchestration when steps or timeouts grow.
- **How do you guarantee exactly-once *effect* on top of at-least-once delivery?** Idempotent producer + idempotent consumer (unique constraint / version guard / inbox in the same transaction).
- **What happens if Redis is down?** Fail-open to L1 and then the DB, bounded by 1 s timeouts. Add a circuit breaker and size the DB for a cold cache.
- **How do you prevent cache invalidation races between the projection and readers?** Evict after commit, double-delete via broadcast, versioned entries, short TTL, CDC ordering.
- **HPA on CPU/memory vs custom metrics?** Memory is a bad JVM signal. Prefer RPS, concurrency, or latency per pod. Lag-based scaling is capped by partitions. Keep the DB connection budget in mind.
- **How do you evolve Kafka event contracts?** Additive-only, defaults in compact constructors, dual-write/dual-read, version bump for breaking changes, consumers deployed first, schema registry at scale.
- **What happens when a Patroni failover occurs mid-request?** In-flight transactions fail, multi-host JDBC finds the new primary, clients retry with the idempotency key, the sync standby gives RPO ≈ 0, RTO ≈ leader TTL + promotion.
- **Why is `LazyConnectionDataSourceProxy` necessary for read/write routing?** The connection must be chosen *after* the read-only flag is bound.
- **What happens if a consumer takes longer than `max.poll.interval.ms`?** It's removed from the group, a rebalance follows, the partition is reassigned, and the message is redelivered (duplicate). Lower `max.poll.records`, speed up processing, and bound the retries.
- **How do you choose the number of partitions?** Target peak consumer parallelism × headroom, and plan for growth because changing it remaps keys. More partitions mean more broker overhead and longer failover.
- **Outbox table is 50M rows and growing. What now?** Check the relay isn't stuck, then partition by time + `DROP PARTITION`, tune autovacuum, keep the partial index.
- **Why not Kafka transactions (EOS) for the outbox relay?** EOS covers Kafka → Kafka. The source here is Postgres, so the DB→Kafka hop is inherently at-least-once (unless you use CDC with exactly-once connectors).
- **How would you detect and handle a poison message in production?** Non-retryable exception classification → DLQ → `DeadLetterQueueArrival` alert → inspect → fix the code or data → replay with the redrive cap.
- **Your p99 is fine but p99.9 is terrible. Where do you look?** GC pauses, CPU throttling, cold pods (JIT), connection-pool waits, retries/timeouts (a 1 s Redis timeout), noisy neighbors, and head-of-line blocking.
- **How do you make the read model consistent for the user who just placed an order?** Return the resource from POST, use a version token, read-from-primary for N seconds after a write, or client-side optimistic UI.
- **How would you implement a global (cluster-wide) rate limit?** A Redis token bucket via an atomic Lua script keyed by client, or edge-level peer-synced tables (HAProxy). Decide fail-open vs fail-closed when Redis is down.
- **What are the risks of `REQUIRES_NEW`?** Two connections per request (pool deadlock under load), and an inner commit that is independent of the outer rollback (by design here).
- **How do you prevent split brain in Postgres HA?** DCS leader lock with TTL (etcd quorum), self-demotion on lock loss, a watchdog, a split-brain alert.
- **Virtual threads: would you enable them tomorrow?** Not without evidence: pinning, pool starvation, and ThreadLocal or context propagation. That's the gating policy of ADR-0027.

---

## 16. Behavioral Questions Anchored in This Project

Use **STAR** (Situation, Task, Action, Result) and include numbers.

- **"Tell me about a hard technical decision."** Outbox vs direct publish vs CDC (ADR-0007): the
  alternatives, why the outbox fit the phase (no new infrastructure, transactional guarantee), and the cost
  accepted (polling latency ~100 ms, table churn, at-least-once semantics).
- **"Tell me about a time you found a production-class bug."** The alert metric name mismatch (§9), the outbox
  lock scope (§7), or the fire-and-forget compensation (§7): how you found it (code review, chaos test), the
  impact, the fix, and the guardrail you added so it can't recur.
- **"How do you make sure a system meets its SLOs?"** Define SLIs, write the k6 scenarios, put
  thresholds in CI, quote evidence (1,043 RPS @ p95 1.91 ms, 0% errors), alert on burn rate, and run chaos tests to
  check resilience claims.
- **"How do you handle disagreement on architecture?"** ADRs with alternatives and trade-offs,
  evidence over opinion (prototypes and load tests), time-boxed decisions, and revisiting when assumptions change
  (e.g. virtual threads stay flag-gated until qualification evidence exists).
- **"How do you mentor or raise the bar?"** Architecture rules encoded as ArchUnit tests,
  runbooks linked from alerts, a definition of done that includes evidence, blameless postmortems.
- **"What would you do differently?"** Choose one topic per aggregate from the start, scope idempotency
  keys per client, adopt OpenTelemetry export early, and tie HPA limits to the DB connection budget (§14).

---

## 17. Hands-on Java Coding Q&A

Practical "show me the code" questions. The core Java snippets below were compiled and run on JDK 21
(the outputs in comments are real).

### 17.1 How do you handle (checked) exceptions inside a Stream?

**Q: `Function`, `Predicate`, and the rest can't throw checked exceptions. What are your options?**
A: There is no single right answer. Pick based on **whether one failure should abort the whole pipeline or
be collected and reported**.

**Option 1: Catch inside the lambda and rethrow unchecked.** The pipeline aborts on the first failure.
Use a JDK unchecked wrapper when one exists (`UncheckedIOException`) so callers can still tell the cause.
```java
List<Integer> ids = lines.stream()
    .map(s -> {
      try {
        return parse(s);                       // throws IOException
      } catch (IOException e) {
        throw new UncheckedIOException(e);      // preserves the cause
      }
    })
    .toList();
```

**Option 2: A reusable "throwing" functional interface + adapter.** This removes the try/catch noise from every
lambda.
```java
@FunctionalInterface
interface ThrowingFunction<T, R, E extends Exception> {
  R apply(T t) throws E;

  static <T, R> Function<T, R> unchecked(ThrowingFunction<T, R, ?> f) {
    return t -> {
      try {
        return f.apply(t);
      } catch (RuntimeException e) {
        throw e;                                   // don't double-wrap
      } catch (Exception e) {
        throw new CompletionException(e);          // or a domain-specific unchecked exception
      }
    };
  }
}

Stream.of("1", "x2").map(ThrowingFunction.unchecked(Snippets::parse)).toList();
// → throws CompletionException, cause: java.io.IOException: bad x2
```

**Option 3: Don't abort. Collect successes *and* failures (a Result/Either type).** This is the best choice for batch jobs,
imports, and event replays, where one bad record shouldn't kill the other 9,999.
```java
sealed interface Result<T> permits Result.Ok, Result.Err {
  record Ok<T>(T value) implements Result<T> {}
  record Err<T>(Object input, Exception error) implements Result<T> {}

  static <T, R> Function<T, Result<R>> lift(ThrowingFunction<T, R, ?> f) {
    return t -> {
      try { return new Ok<>(f.apply(t)); }
      catch (Exception e) { return new Err<>(t, e); }
    };
  }
}

Map<Boolean, List<Result<Integer>>> parts = Stream.of("1", "x2", "3")
    .map(Result.<String, Integer>lift(Snippets::parse))
    .collect(Collectors.partitioningBy(r -> r instanceof Result.Ok));
// ok=2 err=1 → process the OKs, log/report/DLQ the Errs with their input
```

**Option 4: Skip failures (log and drop) with `mapMulti` (Java 16) or `flatMap(Optional)`.** Only do this when
dropping is acceptable, and **always log or count** what was dropped.
```java
List<Integer> ok = Stream.of("1", "x2", "3").<Integer>mapMulti((s, sink) -> {
  try { sink.accept(parse(s)); }
  catch (IOException e) { log.warn("skip {}", s, e); skipped.increment(); }
}).toList();                                    // [1, 3]
```

**Option 5: Use a plain `for` loop.** If the logic has checked exceptions, retries, or early exit,
a loop is often the most readable choice. Streams are not a goal in themselves.

Things to mention as a senior engineer:
- **"Sneaky throw"** (a generics trick that rethrows a checked exception without declaring it) compiles, but it
  hides checked exceptions from callers. Avoid it in application code.
- Streams are **lazy**: the exception is thrown at the *terminal* operation, not where you wrote `.map(...)`. This often
  surprises people when the stream is built in one method and consumed in another.
- In **parallel streams**, an exception from any worker propagates to the caller, and the other tasks may still be
  running or may already have produced side effects. Never rely on partial side effects.
- `Files.lines(path)` must be closed (try-with-resources), and I/O errors during iteration surface as
  `UncheckedIOException`.
- Don't swallow `InterruptedException` inside lambdas. Restore the flag: `Thread.currentThread().interrupt()`.
- Repo tie-in: a Kafka listener that throws goes to `DefaultErrorHandler` (retry with backoff, then DLQ). Exceptions
  are the *signal*, so the projection code lets them propagate instead of catching them.

### 17.2 How many ways can you implement a functional interface?

**Q: Show every way to provide a `Predicate<String>` (or any SAM type), and how they differ.**
A: Seven common ways:
```java
// 1. Lambda expression
Predicate<String> isEmpty1 = s -> s.isEmpty();

// 2. Method reference: four kinds
Predicate<String> isEmpty2 = String::isEmpty;          // unbound instance method
Function<String, Integer> parse = Integer::parseInt;    // static method
Supplier<List<String>> factory = ArrayList::new;        // constructor
Predicate<String> inSet = allowedSkus::contains;        // bound instance method (captures allowedSkus)

// 3. Anonymous inner class
Predicate<String> isEmpty3 = new Predicate<>() {
  @Override public boolean test(String s) { return s.isEmpty(); }
};

// 4. Named (top-level / nested) class or record: reusable, testable, can hold config
record MinLength(int min) implements Predicate<String> {
  public boolean test(String s) { return s.length() >= min; }
}
Predicate<String> min3 = new MinLength(3);

// 5. Enum implementing the interface: a closed set of strategies
enum Discount implements UnaryOperator<Integer> {
  NONE { public Integer apply(Integer p) { return p; } },
  HALF { public Integer apply(Integer p) { return p / 2; } }
}

// 6. Composition via default/static methods: build new functions from existing ones
Predicate<String> valid = min3.and(Predicate.not(String::isBlank)).or(isEmpty1);
Function<Integer, Integer> plus1 = x -> x + 1;
plus1.andThen(x -> x * 2).apply(3);              // (3+1)*2 = 8
plus1.compose((Integer x) -> x * 2).apply(3);    // (3*2)+1 = 7
Comparator<Order> cmp = Comparator.comparing(Order::status).thenComparing(Order::total, Comparator.reverseOrder());

// 7. Higher-order factory method that returns a lambda (closure over parameters)
static Predicate<Order> totalAbove(int threshold) { return o -> o.total() > threshold; }
```
Differences interviewers look for:
- **`this`**: inside a lambda, `this` is the *enclosing* instance. Inside an anonymous class, it is the anonymous
  object itself. Lambdas don't introduce a new scope, so you can't redeclare an enclosing local variable's name.
- **Captured variables** must be *effectively final*, for both lambdas and anonymous classes (Java 8+).
- **Identity and allocation**: non-capturing lambdas are typically singletons (created once via `invokedynamic` /
  `LambdaMetafactory`). Capturing lambdas allocate per evaluation. Anonymous classes create a `.class` file
  and allocate a new instance every time.
- **State**: a named class, record, or enum can carry configuration and be unit-tested and reused. Lambdas
  are best for short, one-off behavior.
- **Checked exceptions**: you can declare your own SAM type with `throws` (see `ThrowingFunction` above).
- **Serialization**: lambdas are serializable only if the target type is (`(Predicate<String> & Serializable) s -> …`),
  and it is fragile. Avoid it.
- `@FunctionalInterface` is optional, but it makes the compiler reject a second abstract method. `default`, `static`,
  and `Object` methods (`equals`) don't count.

### 17.3 Unmodifiable vs immutable collections — when and which?

**Q: What's the difference between `Collections.unmodifiableList`, `List.of`, `List.copyOf`, `Stream.toList()`,
`Collectors.toList()`, and `Arrays.asList`?**
A:
| API | Modifiable? | Nulls? | Backed by source? | Notes |
|---|---|---|---|---|
| `new ArrayList<>(src)` | yes | yes | no (copy) | default mutable list |
| `Collections.unmodifiableList(src)` | no (UOE) | yes | **yes — a read-only *view*** | changes to `src` are visible through the view |
| `List.of(...)` | no | **no (NPE)** | no | even `contains(null)` throws NPE. Compact, value-based |
| `List.copyOf(src)` | no | **no (NPE)** | no (copy, or returns `src` if it's already an immutable copy) | the go-to defensive copy |
| `Stream.toList()` (16+) | no | **yes** | no | unmodifiable but **allows null** |
| `Collectors.toList()` | *unspecified* (ArrayList today) | yes | no | don't rely on mutability. Use `toCollection(ArrayList::new)` if you need it |
| `Collectors.toUnmodifiableList()` | no | **no (NPE)** | no | |
| `Arrays.asList(arr)` | **fixed-size**: `set` works, `add`/`remove` throw UOE | yes | **yes — writes through to the array** | |
| `Map.of(k1,v1,…)` / `Map.ofEntries` | no | no | no | duplicate keys → `IllegalArgumentException`. Iteration order is unspecified |

Verified behavior:
```java
List<String> backing = new ArrayList<>(List.of("a"));
List<String> view = Collections.unmodifiableList(backing);
backing.add("b");
view;                                  // [a, b]  ← the "unmodifiable" view changed!
List<String> copy = List.copyOf(backing);
backing.add("c");
copy;                                  // [a, b]  ← a true snapshot

List.of("a").contains(null);           // NullPointerException
Stream.of("a", null).toList();         // [a, null] (ok)
Stream.of("a", null).collect(Collectors.toUnmodifiableList()); // NullPointerException
String[] arr = {"a", "b"};
Arrays.asList(arr).set(0, "z");        // arr[0] is now "z"
```
**When to use what:**
- **Return internal state from a class or API**: return `List.copyOf(internal)` (a snapshot) or
  `Collections.unmodifiableList(internal)` (cheap live view, when callers *should* see updates and you
  control all writers). Never return the mutable field itself.
- **Record components / value objects**: defensive copy in the compact constructor, so the record is really immutable:
  ```java
  record OrderLine(String sku, List<String> tags) {
    OrderLine {
      Objects.requireNonNull(sku, "sku");
      tags = List.copyOf(tags);      // later changes to the caller's list don't leak in
    }
  }
  ```
- **Constants and config**: `List.of`, `Set.of`, `Map.of`, which are compact and safe to share.
- **Shared across threads / cached values** (e.g. DTOs in `NearCache`, which are handed to many request threads at once):
  use immutable collections, so there are no data races and no "someone mutated the cached object" bugs.
- **You need to build then freeze**: build with `ArrayList`/`HashMap` locally, then `List.copyOf` at the
  boundary.
- **You need nulls**: avoid `List.of`/`copyOf`. Better still, avoid nulls in collections.
- **Caveats**: immutability is **shallow**, since the elements themselves may be mutable. Don't rely on `Set.of`/`Map.of`
  iteration order (it is randomized per JVM run). Unmodifiable ≠ thread-safe for a *view* whose backing list
  is mutated concurrently.

### 17.4 Collectors pitfalls you should know by heart

```java
// toMap with duplicate keys → IllegalStateException: Duplicate key
Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> 1));            // throws
// fix: provide a merge function (and optionally the map type)
Stream.of("a", "a", "b").collect(Collectors.toMap(s -> s, s -> 1, Integer::sum, TreeMap::new)); // {a=2, b=1}
// toMap with a null value → NullPointerException (HashMap.merge rejects null)
Stream.of("a").collect(Collectors.toMap(s -> s, s -> (Integer) null));   // throws

// groupingBy + downstream collectors
Map<String, Long> byStatus = orders.stream()
    .collect(Collectors.groupingBy(Order::status, TreeMap::new, Collectors.counting()));
// {CANCELLED=1, PLACED=2}

// partitioningBy always has both keys (true/false), even if empty
// teeing (Java 12): two collectors in one pass
String stats = orders.stream().collect(Collectors.teeing(
    Collectors.counting(), Collectors.summingInt(Order::total), (count, sum) -> count + "/" + sum)); // "3/22"
```

### 17.5 Map idioms: counting, multimaps, compute*

```java
Map<String, Integer> counts = new HashMap<>();
for (String w : words) counts.merge(w, 1, Integer::sum);          // word count, no containsKey dance

Map<String, List<Integer>> multi = new HashMap<>();
multi.computeIfAbsent("k", k -> new ArrayList<>()).add(1);         // multimap

map.getOrDefault(key, 0);                                           // read with default
map.putIfAbsent(key, value);                                        // insert only if missing
```
In `ConcurrentHashMap`, `merge`/`compute*` are atomic per key. In `HashMap` they are not thread-safe at all.

### 17.6 Implement an LRU cache in 10 lines

```java
final class LruCache<K, V> extends LinkedHashMap<K, V> {
  private final int capacity;
  LruCache(int capacity) { super(16, 0.75f, true); this.capacity = capacity; } // accessOrder = true
  @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > capacity; }
}
// put 1, put 2, get 1, put 3 → keys [1, 3] (2 was least recently used)
```
Follow-ups: it is not thread-safe (wrap it in `Collections.synchronizedMap` or use a lock). In production use **Caffeine**
(W-TinyLFU, concurrent, TTL, metrics), which is what this repo does.

### 17.7 Implement a token-bucket rate limiter

```java
final class TokenBucket {
  private final long capacity;
  private final double refillPerNano;
  private double tokens;
  private long lastRefill;

  TokenBucket(long capacity, long refillPerSecond) {
    this.capacity = capacity;
    this.refillPerNano = refillPerSecond / 1_000_000_000.0;
    this.tokens = capacity;
    this.lastRefill = System.nanoTime();          // monotonic clock, never currentTimeMillis
  }

  synchronized boolean tryAcquire() {
    long now = System.nanoTime();
    tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);   // lazy refill
    lastRefill = now;
    if (tokens >= 1) { tokens -= 1; return true; }
    return false;
  }
}
// capacity 2, 1 token/s: true, true, false
```
Talking points: lazy refill (no background thread), the monotonic clock, `synchronized` (simple; under high contention
use CAS or striping), one bucket per client in a `Caffeine` cache with expiry (like `ClientRateLimitFilter`),
and for a cluster-wide limit, the same algorithm as an atomic **Redis Lua** script.

### 17.8 Concurrency: run calls in parallel with timeouts and fallbacks

```java
ExecutorService io = Executors.newFixedThreadPool(4);       // dedicated pool for blocking I/O, not commonPool
try {
  CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> callA(), io);
  CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> callB(), io)
      .completeOnTimeout("B-fallback", 100, TimeUnit.MILLISECONDS);         // per-call timeout + fallback
  CompletableFuture<String> c = CompletableFuture.supplyAsync(() -> callC(), io)
      .exceptionally(ex -> "C-fallback(" + ex.getCause().getMessage() + ")"); // ex is a CompletionException
  String result = a.thenCombine(b, String::concat).thenCombine(c, String::concat)
      .get(1, TimeUnit.SECONDS);                                              // overall deadline
} finally {
  io.shutdown();                                         // proper shutdown: stop accepting work
  if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow();
}
```
Pitfalls: `orTimeout` / `completeOnTimeout` don't cancel the underlying work (the thread keeps running), exceptions
are wrapped in `CompletionException`, and an unbounded `newCachedThreadPool` under load creates unlimited threads.

### 17.9 try-with-resources and suppressed exceptions

```java
try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
  // resources close in reverse order, even on exception
}
// If the body throws AND close() throws, the body's exception wins and close()'s is attached:
//   e.getMessage() → "body failed", e.getSuppressed()[0] → "close failed"
```
Never `return` from `finally` (it silently discards exceptions), and don't catch `Throwable`/`Error` unless you rethrow.

### 17.10 Money, dates, and equality traps

```java
new BigDecimal("1.0").equals(new BigDecimal("1.00"));        // false: equals compares scale
new BigDecimal("1.0").compareTo(new BigDecimal("1.00")) == 0; // true:  use compareTo for value equality
new BigDecimal(0.1);       // 0.1000000000000000055511151231257827021181583404541015625
BigDecimal.valueOf(0.1);   // 0.1 → always construct from String or valueOf, never from a double
```
- Never use `double` for money. Use `BigDecimal` with an explicit `RoundingMode`, or **integer minor units**. This repo's
  `Money` record wraps an `int` amount (validated `0..1_000_000_000`) and the DB column is
  `price INTEGER CHECK (price >= 0)`.
- Time: store **`Instant`** / `TIMESTAMPTZ` (UTC) and convert to a zone only for display. `LocalDateTime` has no zone,
  so it is ambiguous across DST and regions. Inject a `java.time.Clock` so time-dependent code (TTL, expiry,
  idempotency `expires_at`) is testable.
- `Integer` caching: `Integer.valueOf(127) == Integer.valueOf(127)` is true, but `128 == 128` boxed is false. Always
  use `equals` for boxed types.

### 17.11 Modern Java: sealed types + pattern matching

```java
sealed interface Result<T> permits Result.Ok, Result.Err { /* records as above */ }

String describe(Result<Integer> r) {
  return switch (r) {                                    // exhaustive: no default needed
    case Result.Ok<Integer>(Integer v) when v > 3 -> "big " + v;   // record pattern + guard
    case Result.Ok<Integer> ok                    -> "ok " + ok.value();
    case Result.Err<Integer> err                  -> "err " + err.error().getMessage();
  };
}
```
If someone adds a new permitted subtype, every non-exhaustive `switch` **fails to compile**. That is the main
benefit compared to `instanceof` chains. Repo example: `IdempotencyDecision` (`Proceed` | `Replay`).

### 17.12 Sorting with nulls and multiple keys

```java
names.sort(Comparator.nullsLast(Comparator.naturalOrder()));       // [a, b, null]
orders.sort(Comparator.comparing(Order::status)
    .thenComparing(Order::createdAt, Comparator.reverseOrder())
    .thenComparingLong(Order::id));                                 // deterministic tie-breaker
```
Always add a unique tie-breaker when the sort feeds pagination. The repo's `listOrders` orders by
`created_at DESC, order_id DESC` for this reason.

### 17.13 Thread-safe lazy singleton

```java
final class ExpensiveClient {
  private ExpensiveClient() {}
  private static final class Holder { static final ExpensiveClient INSTANCE = new ExpensiveClient(); }
  static ExpensiveClient get() { return Holder.INSTANCE; }   // lazy + thread-safe via class init, no locks
}
```
In Spring, you almost never need this: a singleton bean is created once by the container.

### 17.14 Producer–consumer with backpressure

```java
BlockingQueue<Event> queue = new ArrayBlockingQueue<>(1_000);   // bounded = backpressure
// producer
if (!queue.offer(event, 50, TimeUnit.MILLISECONDS)) {
  rejected.increment();                                         // shed or fail fast, don't block forever
}
// consumer thread
while (!Thread.currentThread().isInterrupted()) {
  Event e = queue.poll(1, TimeUnit.SECONDS);
  if (e != null) handle(e);
}
```
Use a bounded queue, timeouts on `offer`/`poll`, a rejection metric, and shutdown via interrupt. In this system Kafka
plays this role *between* services, and the outbox table plays it between the DB and Kafka.

---
## 18. Hands-on Spring, Kafka & SQL Q&A

### 18.1 Consistent API errors with `ProblemDetail` (RFC 9457)

```java
@RestControllerAdvice
class ApiErrorHandler {
  @ExceptionHandler(IdempotencyConflictException.class)
  ProblemDetail conflict(IdempotencyConflictException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    pd.setTitle("Request already in progress");
    pd.setProperty("correlationId", MDC.get("correlationId"));   // lets support find the logs
    return pd;
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception ex) {
    log.error("Unhandled error", ex);                            // log once, here, with the stack trace
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"); // no internals leaked
  }
}
```
Talking points: map domain exceptions to stable status codes (the repo uses 400 / 409 / 422 / 500), never leak stack
traces or SQL, include a correlation ID, and log server errors once at the boundary, not at every layer.

### 18.2 Make `@Transactional` roll back on checked exceptions

```java
@Transactional(rollbackFor = Exception.class)       // the default only rolls back on RuntimeException/Error
public void importCatalog(Path file) throws IOException { ... }
```

### 18.3 Run side effects only *after* the transaction commits

Evicting a cache or calling an external system *inside* the transaction is a bug: if the transaction rolls back, the
side effect already happened. If the cache is refilled before the commit, it caches the old value.
```java
@Transactional
public void updatePrice(long id, int price) {
  repository.updatePrice(id, price);
  events.publishEvent(new ProductChanged(id));       // ApplicationEventPublisher
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
void onProductChanged(ProductChanged e) {
  catalogService.evictProduct(e.id());               // runs only if the commit succeeded
}
```
For events that *must not be lost*, use the outbox instead. `AFTER_COMMIT` listeners run in memory and are lost if the
pod dies right after the commit.

### 18.4 Idempotent Kafka consumer with an inbox table — and the ack-ordering trap

```sql
CREATE TABLE billing.processed_events (event_id VARCHAR(100) PRIMARY KEY, processed_at TIMESTAMPTZ DEFAULT now());
```
```java
@Service
class BillingHandler {
  @Transactional
  public void handle(OrderPlaced e) {
    int inserted = jdbc.update(
        "INSERT INTO billing.processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", e.eventId());
    if (inserted == 0) return;                // duplicate delivery → no-op
    createInvoice(e);                          // side effect in the SAME transaction as the dedup row
  }
}

@KafkaListener(topics = "order-placed", groupId = "billing")
void onMessage(String json, Acknowledgment ack) {
  billingHandler.handle(parse(json));          // transaction commits when this returns
  ack.acknowledge();                           // ack AFTER the commit
}
```
**Trap:** with `AckMode.MANUAL_IMMEDIATE`, calling `ack.acknowledge()` *inside* a `@Transactional` listener method
commits the Kafka offset **before** the DB transaction commits. If the DB commit then fails, the offset has already
moved and the message is lost. Keep the transaction in a separate bean and acknowledge after it returns. That is how
this repo's projections are structured: DB write first, then `acknowledgment.acknowledge()` last.

### 18.5 Retry and DLQ configuration (from the repo)

```java
ExponentialBackOffWithJitter backOff = new ExponentialBackOffWithJitter(3);   // 3 retries
backOff.setInitialInterval(200); backOff.setMultiplier(2.0); backOff.setMaxInterval(2000);  // ±50% jitter
DefaultErrorHandler handler = new DefaultErrorHandler(
    new DeadLetterPublishingRecoverer(kafkaTemplate,
        (record, ex) -> new TopicPartition(record.topic() + "-dlq", record.partition())), backOff);
handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);   // poison → DLQ now
factory.setCommonErrorHandler(handler);
```

### 18.6 Testing slices and real infrastructure

```java
@WebMvcTest(OrderController.class)                       // web layer only: fast
class OrderControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean OrderService orderService;                // Spring Framework 6.2+ (replaces @MockBean)
  @MockitoBean IdempotencyService idempotencyService;

  @Test void rejectsInvalidBody() throws Exception {
    mvc.perform(post("/orders").contentType(APPLICATION_JSON).content("{\"items\":[]}"))
       .andExpect(status().isBadRequest());
  }
}

@SpringBootTest
@Testcontainers
class OutboxIntegrationTest {
  @Container @ServiceConnection static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  @Container @ServiceConnection static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka"));

  @Test void orderEventuallyPublished() {
    long id = orderService.createOrder(items).id();
    await().atMost(Duration.ofSeconds(10))
           .until(() -> outboxRepository.isPublished(id));     // poll with a deadline, never Thread.sleep
  }
}
```
`@ServiceConnection` (Boot 3.1+) wires the container's URL and credentials into the auto-configuration, so no
`@DynamicPropertySource` boilerplate is needed.

### 18.7 SQL you should be able to write on a whiteboard

```sql
-- Duplicates (e.g. double-processed reservations)
SELECT event_id, sku, count(*) FROM inventory.reservations
GROUP BY event_id, sku HAVING count(*) > 1;

-- Top-N per group: latest 3 orders per status
SELECT * FROM (
  SELECT o.*, row_number() OVER (PARTITION BY status ORDER BY created_at DESC, order_id DESC) AS rn
  FROM order_query.order_read_model o
) t WHERE rn <= 3;

-- Keyset pagination (stable and O(log n), unlike OFFSET)
SELECT order_id, status, created_at FROM order_query.order_read_model
WHERE (created_at, order_id) < (:lastCreatedAt, :lastOrderId)
ORDER BY created_at DESC, order_id DESC
LIMIT 20;

-- Version-guarded upsert (the repo's projection pattern): idempotent + out-of-order safe
INSERT INTO order_query.order_read_model (order_id, status, items, version, created_at)
VALUES (:id, :status, :items::jsonb, :version, :createdAt)
ON CONFLICT (order_id) DO UPDATE
  SET status = EXCLUDED.status, items = EXCLUDED.items, version = EXCLUDED.version, updated_at = now()
  WHERE order_query.order_read_model.version <= EXCLUDED.version;

-- Atomic conditional decrement (no oversell, no SELECT-then-UPDATE race)
UPDATE inventory.stock SET available = available - :qty
WHERE sku = :sku AND available >= :qty;          -- 0 rows updated → out of stock

-- Work queue claim (the outbox pattern)
SELECT id FROM "order".outbox_events
WHERE published_at IS NULL ORDER BY created_at LIMIT 500
FOR UPDATE SKIP LOCKED;
```

### 18.8 Kafka CLI you'll actually use

```bash
# Consumer lag per partition (LAG column) and which member owns each partition
kafka-consumer-groups --bootstrap-server kafka:9092 --describe --group order-query

# Topic health: leader, replicas, ISR (ISR < min.insync.replicas → producers fail with acks=all)
kafka-topics --bootstrap-server kafka:9092 --describe --topic order-placed

# Peek at messages with keys and headers (find events for one order)
kafka-console-consumer --bootstrap-server kafka:9092 --topic order-placed-dlq --from-beginning \
  --property print.key=true --property print.headers=true --max-messages 20

# Rewind a group (the group must be stopped). Dry-run first, then --execute
kafka-consumer-groups --bootstrap-server kafka:9092 --group order-query --topic order-placed \
  --reset-offsets --to-datetime 2026-10-26T00:00:00.000 --dry-run
```
(The Confluent image names the tools without `.sh`. Apache distributions use `kafka-consumer-groups.sh`.)

---

## 19. Production Troubleshooting Playbook — "How do you narrow down and identify the problem?"

**Q: Something is wrong in production. Walk me through how you narrow it down.**
A: Use a repeatable method rather than guessing. **Mitigate first, then find the root cause.**

1. **Triage the impact (first 5 minutes).** What is the symptom: errors, latency, wrong data, or an outage? Who is affected:
   all users, or one endpoint, tenant, region, or pod? Is it getting worse? This decides severity and whether to page more people.
2. **Ask "what changed?"** Most incidents are caused by a change: a deploy, a config or feature flag, a traffic
   shift (campaign, bot), a dependency (DB failover, Kafka broker loss, expired certificate), infrastructure (node drain,
   autoscaling), or data (a new kind of payload, a huge customer). Line up the symptom start time against
   deploy history (`kubectl rollout history`, the git-revision annotation), alerts, and change logs.
3. **Narrow by dimension.** Split the golden signals by `uri`, `status`, `method`, `instance` (pod),
   version, and node. *One pod bad* points to a node, noisy neighbor, bad cache, or GC. *One endpoint bad* points to its query
   or dependency. *Everything bad* points to a shared dependency (DB, Kafka, Redis, network) or the ingress.
4. **Narrow by layer (follow the request).** Edge (HAProxy 429s or 5xx?) → app (Tomcat threads, GC, CPU, errors in
   logs) → dependencies (Hikari pending, DB wait events, Kafka lag, Redis timeouts). Find the **first layer
   that looks unhealthy**. Upstream layers usually only show *symptoms* of the downstream problem.
5. **Follow one concrete example.** Take one failing request's `X-Correlation-Id` / `traceId`, grep it across all
   services' JSON logs, and reconstruct exactly what happened. One real trace beats ten dashboards.
6. **Form a hypothesis and test it** with data (a query, a thread dump, a reproduction in staging). Change one thing at a time.
7. **Mitigate**: roll back, disable the feature flag, scale out, shed load, fail over, or take the bad pod out of rotation.
   Restore service first, and capture evidence (thread dumps, heap info, logs) *before* restarting, because restarts
   destroy evidence.
8. **Root cause and prevention**: use 5 Whys to get past "the DB was slow" to the real cause. Then write a blameless
   postmortem with action items: an alert that would have caught it earlier, a test, a guardrail, a runbook update.

> One-liner for interviews: *"Scope it, find what changed, split the metrics by dimension until one
> pod/endpoint/dependency stands out, follow one real request end to end by correlation ID, mitigate
> first, then prove the root cause with data and add a guardrail."*

### 19.1 Toolbox (with commands for this repo)

```bash
NS=hyperscale
# Pods, restarts, placement
kubectl -n $NS get pods -l app.kubernetes.io/component=app -o wide
kubectl -n $NS describe pod <pod>                 # Last State: OOMKilled / exit 137, probe failures, events
kubectl -n $NS get events --sort-by=.lastTimestamp | tail -30
kubectl -n $NS top pods                           # CPU/memory now (needs metrics-server)
kubectl -n $NS rollout history deployment/app

# Logs: JSON, so filter with jq. With a label selector, --tail defaults to 10, so set it explicitly
kubectl -n $NS logs -l app.kubernetes.io/component=app --since=15m --tail=-1 \
  | jq -Rc 'fromjson? | select(.correlationId=="<id>")'
kubectl -n $NS logs <pod> --previous              # logs from the crashed container

# JVM diagnostics inside the pod (the image is a full JDK; java runs as PID 1)
kubectl -n $NS exec <pod> -c app -- jcmd 1 Thread.print -l > threads-1.txt
kubectl -n $NS exec <pod> -c app -- jcmd 1 GC.heap_info
kubectl -n $NS exec <pod> -c app -- jcmd 1 GC.class_histogram | head -30
kubectl -n $NS exec <pod> -c app -- jcmd 1 VM.native_memory summary   # NMT is enabled in the Dockerfile
kubectl -n $NS exec <pod> -c app -- jcmd 1 JFR.start duration=60s filename=/diagnostics/rec.jfr
kubectl -n $NS cp <pod>:/diagnostics/rec.jfr ./rec.jfr -c app
# or the repo wrapper:
performance/jvm/capture-jvm-diagnostics.sh --pod <pod> --container app

# Talk to one specific pod (bypass the load balancer)
kubectl -n $NS port-forward pod/<pod> 18080:8080
curl -s localhost:18080/actuator/health | jq
curl -s localhost:18080/actuator/prometheus | grep -E 'hikaricp_connections_(active|pending)|outbox_relay_lag|kafka_consumer_lag'

# Take a bad pod out of rotation but keep it for debugging: removing the selector label detaches it from
# the Service and the ReplicaSet, which starts a fresh replacement
kubectl -n $NS label pod <pod> app.kubernetes.io/component-
```

PromQL for narrowing:
```promql
# Which endpoints are failing?
sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
# Is it one pod?
topk(5, sum by (instance) (rate(http_server_requests_seconds_count{status=~"5.."}[5m])))
# p95 per pod for one route (aggregate buckets first, then the quantile)
histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{uri="/orders/{id}"}[5m])))
# Waiting on the DB pool?
max by (instance, pool) (hikaricp_connections_pending)
# GC pressure / CPU throttling
rate(jvm_gc_pause_seconds_sum[5m])
rate(container_cpu_cfs_throttled_periods_total[5m]) / rate(container_cpu_cfs_periods_total[5m])
```

PostgreSQL queries:
```sql
-- What is running right now, oldest transactions first (long transactions block VACUUM and hold locks)
SELECT pid, now() - xact_start AS xact_age, state, wait_event_type, wait_event, client_addr, left(query, 120)
FROM pg_stat_activity
WHERE datname = 'hyperscale' AND state <> 'idle'
ORDER BY xact_age DESC NULLS LAST LIMIT 20;

-- Who is blocking whom
SELECT pid, pg_blocking_pids(pid) AS blocked_by, wait_event, left(query, 120)
FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0;

-- Connections per client (pod IP) and state: is one pod hoarding connections? idle in transaction?
SELECT client_addr, state, count(*) FROM pg_stat_activity GROUP BY 1, 2 ORDER BY 3 DESC;

-- Top queries by total time (requires the pg_stat_statements extension)
SELECT calls, round(total_exec_time) AS total_ms, round(mean_exec_time::numeric, 2) AS mean_ms, left(query, 100)
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;

-- Bloat / vacuum health (the outbox is the usual suspect)
SELECT relname, n_live_tup, n_dead_tup, last_autovacuum FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 10;

-- Replication state (sync standby present? lag?)
SELECT application_name, state, sync_state, replay_lag FROM pg_stat_replication;
-- Last resort for a runaway session: SELECT pg_terminate_backend(<pid>);
```

### 19.2 Scenario: 5xx spike right after a deploy
1. Confirm the correlation: the error start time matches the rollout, and errors appear only on new-revision pods (`topk by instance`).
2. **Mitigate immediately**: `kubectl -n hyperscale rollout undo deployment/app`. `maxUnavailable: 0` keeps capacity during the rollback.
3. Then diagnose from the logs of the bad revision: a new exception type? a config or env difference? a migration that the old code
   can't handle (this is why migrations must be backward compatible)?
4. Prevention: canary or progressive delivery with automatic rollback on SLO burn, plus a contract or integration test for the bug.

### 19.3 Scenario: latency is high but CPU is low
High latency with low CPU means threads are **waiting**, not working. Take 3 thread dumps about 5 s apart and group them by state and frame:
- many threads in `HikariPool.getConnection` → **DB pool exhaustion** (see 19.6),
- many in `SocketInputStream.read` / Lettuce → waiting on a slow dependency (Redis timeout 1 s, so check `RedisNodeDown`),
- many `BLOCKED` on the same monitor → **lock contention** (find the owner thread in the dump),
- `WAITING` in `CompletableFuture.get` → waiting on async work (e.g. the outbox relay waiting up to 10 s).
Then confirm with metrics (`hikaricp_connections_pending`, dependency latency) and fix the slow dependency, not the symptom.

### 19.4 Scenario: CPU at 100%
1. Is it all pods (load increase → check RPS; scale out or shed) or one (a hot loop, a pathological input, GC)?
2. GC? Check `rate(jvm_gc_pause_seconds_sum[5m])` and heap usage after GC. If the heap is nearly full, GC thrashes, so it's really a memory problem (19.5).
3. Otherwise find the hot code: a 60 s JFR recording (Method Profiling / hot methods), or `top -H -p 1` in the container →
   convert the thread ID to hex → find that `nid=0x…` in the thread dump. Typical causes: regex backtracking, JSON
   (de)serialization of huge payloads, accidental O(n²) (for example scanning a 10k-entry cache key set on every invalidation),
   busy-spin retries without backoff.
4. Also check **CPU throttling**: with a 2-CPU limit, average CPU can look fine while the pod is throttled in bursts, which
   adds latency.

### 19.5 Scenario: memory keeps growing / pods get OOMKilled
- First decide **which** OOM it is:
  - `java.lang.OutOfMemoryError` in the logs → **heap**. The Dockerfile sets `-XX:+HeapDumpOnOutOfMemoryError
    -XX:HeapDumpPath=/diagnostics/heapdump.hprof -XX:+ExitOnOutOfMemoryError`, so you get a dump and a clean restart.
  - `kubectl describe pod` shows `OOMKilled` / exit code 137 with *no* Java OOM → the **container** limit (1 GiB) was hit by
    heap + **native memory** (thread stacks, metaspace, direct buffers). `-XX:MaxRAMPercentage=75` leaves only ~256 MiB for
    everything else. Check `jcmd 1 VM.native_memory summary` (NMT is enabled).
- Heap leak workflow: compare two `GC.class_histogram` outputs taken minutes apart. The classes whose counts keep growing are
  the suspects. Then open the heap dump in Eclipse MAT, look at the dominator tree, and follow the "path to GC roots". Usual
  suspects: unbounded maps or caches, listeners or ThreadLocals never removed, huge result sets loaded into memory.
- Mitigation while you investigate: increase the memory limit or lower `MaxRAMPercentage`, and restart on a schedule only as a stopgap.

### 19.6 Scenario: DB connection pool exhaustion
Symptoms: `Connection is not available, request timed out after 5000ms`, rising `hikaricp_connections_pending`,
`HikariCpConnectionTimeout` firing, and latency pinned near 5 s.
1. In `pg_stat_activity`: are connections `active` (slow queries → `pg_stat_statements` / `EXPLAIN (ANALYZE, BUFFERS)`),
   `idle in transaction` (code holding a transaction open while doing I/O, e.g. an HTTP or Kafka call inside `@Transactional`), or
   blocked (`pg_blocking_pids` → a lock chain, often a migration or a long batch job)?
2. In the app's thread dump: who *holds* connections, i.e. which stack frames are inside JDBC calls?
3. Mitigate: terminate the rogue sessions, roll back the offending change, temporarily shed degradable traffic.
4. Fix: move I/O out of transactions, add indexes or fix queries, set `statement_timeout` / `idle_in_transaction_session_timeout`,
   keep `REQUIRES_NEW` (2 connections per request) off hot paths, and re-check the pod × pool-size budget against `max_connections`.

### 19.7 Scenario: "My order doesn't show up in GET /orders" (data flow investigation)
Follow the data through each hop until you find where it stopped:
```sql
-- 1. Command side: does the order exist?
SELECT id, status, created_at FROM "order".orders WHERE id = :id;
-- 2. Outbox: was the event written, and has it been published?
SELECT id, event_type, created_at, published_at FROM "order".outbox_events WHERE aggregate_id = :id::text;
--    published_at IS NULL → relay problem: check outbox_relay_lag, relay logs, and Kafka health
```
```bash
# 3. Kafka: did it reach the topic or the DLQ? (search by key = order id)
kafka-console-consumer --bootstrap-server kafka:9092 --topic order-placed-dlq --from-beginning \
  --property print.key=true --property print.headers=true | grep '^<id>'
# 4. Consumer: is order-query lagging or stuck on a partition?
kafka-consumer-groups --bootstrap-server kafka:9092 --describe --group order-query
```
```sql
-- 5. Read model: is the row there, and with which version?
SELECT order_id, status, version, updated_at FROM order_query.order_read_model WHERE order_id = :id;
```
6. Cache: if the row is correct but the API is wrong, it's a stale L1/L2 entry. Check the invalidation consumer lag
   (`CacheInvalidationLagHigh`), and evict the key or wait out the TTL.
7. Logs: grep the original request's `correlationId` across `app` and `order-query`. It is carried through the outbox
   payload and the Kafka headers, so it links every hop.
Typical answers: Kafka was down (backlog in the outbox, drains automatically), a poison event in the DLQ (fix, then replay with
`/admin/dlq/replay`), consumer lag (scale up to the partition count, fix the slow handler), or stale cache.

### 19.8 Scenario: Kafka consumer lag keeps growing
1. `kafka-consumer-groups --describe`: is lag on **all** partitions (throughput problem) or **one** (a hot key, or a
   poison record being retried. Check the logs for repeated failures at the same offset)?
2. Frequent rebalances? Look for "Revoking / Assigned partitions" in the logs. Usually processing is exceeding `max.poll.interval.ms`
   (500 records × slow DB writes), or pods are crash-looping or being autoscaled.
3. Slow handler? Check DB latency for the projection's upsert and the consumer's thread dump.
4. Mitigate: scale consumers up to the **partition count** (3 here, more pods won't help beyond that), reduce
   `max.poll.records`, fix the slow query, or move the poison record to the DLQ.
5. Long-term: more partitions (plan the key remapping), batch processing, and broker-side lag alerting.

### 19.9 Scenario: one pod is bad, the others are fine
Compare the bad pod with a good one: node (`-o wide`, node pressure, noisy neighbor), restarts, GC, its Redis or DB
connectivity, its L1 cache contents (each pod has its own L1, so a missed invalidation produces exactly this
"only sometimes wrong" behavior), and clock skew. Take it out of rotation by removing its label (19.1), capture diagnostics, then
delete it. If the bad pods cluster on one node, cordon and drain that node.

### 19.10 Scenario: CrashLoopBackOff / pods never become Ready
- `kubectl logs <pod> --previous` and `describe pod`: an exception at startup (bad config, a missing secret, a Flyway
  migration failure, a dependency not reachable), or a **probe misconfiguration** (liveness killing a slow-starting JVM.
  That's why there is a `startupProbe` allowing 30 × 3 s), or OOMKilled during startup.
- Readiness never passes: check `/actuator/health/readiness` on the pod via port-forward.
- Remember that `minAvailable: 2` PDBs plus `maxUnavailable: 0` mean a broken rollout stalls rather than taking the service down.
  That is the safety net working. Roll back.

### 19.11 Scenario: intermittent errors that you can't reproduce
Gather more signal before guessing: group the errors by pod, node, endpoint, client, payload size, and time of day (cron jobs,
the hourly pruning job, backups). Look for timeouts at exact values (1 s Redis, 5 s Hikari, 10 s outbox publish, 30 s
HAProxy), which point to the component that timed out. Add temporary targeted logging or raise the sampling rate.
Check for retry storms (errors that cause retries that cause more errors). If it correlates with
deploys or scaling, suspect connection resets during shutdown (preStop, graceful shutdown timing).

### 19.12 Postmortem template (blameless)
- **Summary**: what happened, in 2 sentences.
- **Impact**: who and what was affected, for how long, how many failed requests or orders, SLO budget burned.
- **Timeline** (UTC): first signal → detection → mitigation → resolution. Note the detection gap.
- **Root cause**: the 5 Whys, technical and process (why did review, tests, and alerts not catch it?).
- **What went well / what went poorly / where we got lucky**.
- **Action items**: each with an owner and a due date. Prefer guardrails (alerts, tests, automation, limits) over "be more careful".
