# HyperScale Commerce — Interview Prep

## 1. Project Introduction

**HyperScale Commerce** is a commerce platform that evolves incrementally from a
modular Spring Boot monolith into a resilient, cloud-native distributed system.
The evolution is driven by a phased "BootCamp" program; the repo is currently at
**Phase 5 — Service Extraction**, where the read side (`order-query`) has been
split out of the `app` monolith.

### Tech stack
- **Language / Runtime:** Java (JDK 21), Spring Boot, Gradle multi-project build
- **Persistence:** PostgreSQL 16 (HA via Patroni), jOOQ, Flyway migrations, HikariCP pool
- **Messaging:** Apache Kafka (async event backbone)
- **Caching:** Caffeine (L1 near cache) + Redis (L2 distributed cache)
- **Infra:** Kubernetes + Helm, HAProxy ingress, etcd
- **Observability:** Spring Boot Actuator + Prometheus, Grafana dashboards, Logback JSON logging
- **Performance/Chaos:** k6 load tests, Phase-18 qualification harness

### High-level architecture
- `app/` — Spring Boot modular monolith. Command side: catalog reads, order
  commands, inventory. Publishes domain events through a **transactional outbox**.
  Runs on port **8080**.
- `order-query/` — dedicated **CQRS read-model** service. Consumes order events
  from Kafka, projects them into a read-optimized schema, serves `GET /orders*`.
  Runs on port **8081**.
- `contracts/` — shared event contract definitions for type-safe inter-service messaging.
- `performance/` — k6 scripts, Helm chart, monitoring dashboards/alerts, chaos tooling.

### Key distributed-systems patterns implemented
- **Transactional Outbox** for reliable at-least-once event publishing
- **CQRS** (command monolith + read-model service)
- **Choreographed Saga + compensation** for multi-context order workflows
- **API Idempotency** via `Idempotency-Key` header
- **Multi-tier caching** with event-driven invalidation
- **Adaptive load shedding + rate limiting** for overload protection
- **DLQ + replay** for stream-processing resilience

---

## 2. Java — Q&A

**Q: What are the four OOP pillars?**
A: Encapsulation (scoping state/behavior via access modifiers), Inheritance
(an "is-a" relationship where a child inherits fields/methods), Polymorphism
(treating different types through a common interface), Abstraction (hiding
implementation and exposing only essential features).

**Q: Inheritance vs composition — when do you prefer composition?**
A: Inheritance is a tightly-coupled "is-a" relationship fixed at compile time.
Composition is a loosely-coupled "has-a" relationship that can be swapped at
runtime. Prefer composition for flexibility and to avoid fragile class hierarchies.

**Q: Abstract class vs interface?**
A: An abstract class models an "is-a" relationship and can hold constructors,
fields, concrete methods and internal state. An interface defines *what* a type
can do; it can have `default` methods but no constructors, instance fields, or
internal state. A class can implement many interfaces but extend one abstract class.

**Q: What is a functional interface? Name the core types.**
A: An interface with exactly one abstract method. Core ones: `Predicate<T>`
(arg → boolean), `Function<T,R>` (arg → transformed result), `Consumer<T>`
(arg → void), `Supplier<T>` (nothing → result).

**Q: Stream API — intermediate vs terminal operations?**
A: Intermediate ops (`map`, `filter`) are lazy and return another stream, forming
a chain. Terminal ops (`collect`, `forEach`, `reduce`) trigger evaluation and
produce a non-stream result.

**Q: Explain `final`, `volatile`, `synchronized`, `transient`, `native`.**
A: `final` = immutable reference/method/class. `volatile` = guarantees cross-thread
*visibility* but not atomicity. `synchronized` = mutual exclusion so only one thread
runs a block/method. `transient` = field skipped during serialization (restored to
default on deserialize). `native` = method implemented in native (non-Java) code.

**Q: Race condition vs visibility bug?**
A: A race condition is incorrect behavior when multiple threads mutate shared state
concurrently and ordering is non-deterministic. A visibility bug is when one thread's
write to shared state is not observed by another thread (fixed with `volatile`,
`synchronized`, or proper happens-before relationships).

**Q: `synchronized` vs `ReentrantLock`?**
A: `synchronized` is a keyword with implicit acquire/release. `ReentrantLock` is an
explicit lock (manual `lock()`/`unlock()`, always in `finally`) offering `tryLock`,
timed/interruptible acquisition, and fair/unfair ordering.

**Q: `ArrayList` vs `LinkedList` internally?**
A: `ArrayList` is backed by a contiguous array that grows by copying into a larger
array; O(1) random access, O(n) middle insert. `LinkedList` is node-based with
pointers; O(1) insert/remove at known position but O(n) random access.

**Q: HashMap — relationship between `equals` and `hashCode`?**
A: Contract: if `a.equals(b)` is true, `a.hashCode() == b.hashCode()` **must** be
true. The reverse need not hold (hash collisions are allowed). Internally HashMap
is an array of buckets; each bucket is a linked list that converts to a red-black
tree once it crosses the treeify threshold (with a minimum table capacity).

**Q: JVM memory / garbage collection — how does the GC decide what to collect?**
A: GC reclaims heap objects unreachable from **GC roots** (local variables on active
threads, active threads, static references, JNI references, JVM-internal references).
For diagnosing memory growth: heap dumps (object retention) and thread dumps (stuck threads).

**Q: What's wrong with removing from a list while iterating a for-each loop?**
A: It throws `ConcurrentModificationException` (and `List.of(...)` is immutable, so
`remove` throws `UnsupportedOperationException`). Use an explicit `Iterator.remove()`
or `removeIf`.

---

## 3. Spring / Spring Boot — Q&A

**Q: Spring vs Spring Boot?**
A: Spring is a modular framework built around IoC/DI and AOP that manages object
lifecycles. Spring Boot sits on top, removing boilerplate via "convention over
configuration," auto-configuration, embedded servers, and production-ready features.

**Q: How does Spring Boot auto-configuration work?**
A: On startup it inspects the classpath, properties, environment, and already-defined
beans, then conditionally registers beans. It's non-invasive — if you define your own
bean it backs off (via `@ConditionalOnMissingBean`). Other conditionals:
`@ConditionalOnClass`, `@ConditionalOnProperty`, `@ConditionalOnWebApplication`,
`@ConditionalOnBean`.

**Q: What does `@Transactional` do? Explain `REQUIRED` vs `REQUIRES_NEW`.**
A: It binds a method/class to a transaction so all DB ops commit or roll back as a
unit. `REQUIRED` joins an existing transaction or creates one; `REQUIRES_NEW`
suspends any current transaction and starts a fresh independent one.

**Q: `@Transactional` self-invocation problem — why and how to fix?**
A: Calling another `@Transactional` method via `this` bypasses the Spring proxy, so
the annotation is ignored. Fix by moving the method to a separate bean, self-injecting
the proxy, or using `AopContext.currentProxy()`.

**Q: Bean scopes?**
A: `singleton` (default, one per context), `prototype` (new instance per lookup),
plus web scopes `request`, `session`, `application`, `websocket`.

**Q: In this project, which Spring Boot building blocks are used?**
A: Actuator for health/metrics endpoints (`/actuator/health/liveness`,
`/actuator/health/readiness`, `/actuator/prometheus`), profile-based config
(`local` vs `!local`), Flyway for migrations, and Spring Kafka listeners in `order-query`.

---

## 4. Caching — Q&A

**Q: Describe the caching strategy.**
A: Multi-tier: a **Caffeine L1 near cache** in-process plus a **Redis L2 distributed
cache**. `CatalogService` caches products (10k entries / 60s TTL) and product lists
(2k entries / 30s TTL); `OrderQueryService` caches order details (50k entries / 30s TTL).

**Q: How do you avoid cache stampede / thundering herd?**
A: Singleflight concurrent-lookup deduplication — if many requests miss the same key
simultaneously, only one triggers the load and the rest await that single result.

**Q: How is cache consistency maintained?**
A: Event-driven invalidation. `OrderPlacedProjection` and `OrderCancelledProjection`
perform synchronous cache eviction on order state transitions, guaranteeing zero
stale reads for order state.

**Q: How do you observe cache effectiveness?**
A: Caffeine caches register Prometheus metrics. Load test `cached-throughput.js`
sustained ~1,043 RPS with p95 product lookup latency ~1.91ms and 0% error rate.

**Q: Redis configuration choices?**
A: `order-query` uses short 1s connect/read timeouts so a slow cache degrades
gracefully to DB rather than blocking request threads.

---

## 5. Scaling & Distributed Systems — Q&A

**Q: Why the transactional outbox instead of publishing to Kafka directly?**
A: Dual-write between DB and Kafka can't be atomic. The outbox writes the event to
an `outbox_events` row in the *same* DB transaction as the business change; a
background `OutboxRelay` polls and publishes to Kafka, guaranteeing at-least-once
delivery even if Kafka is temporarily down.

**Q: How do multiple app replicas poll the outbox without duplicating events?**
A: The claim query uses PostgreSQL `FOR UPDATE SKIP LOCKED`, so each replica claims
a disjoint batch without lock contention — enabling linear horizontal scaling of pods.

**Q: Explain the CQRS split here.**
A: `app` handles writes and publishes `OrderPlaced`/`OrderCancelled` events;
`order-query` consumes them and projects into an `order_read_model` table optimized
for `GET /orders*`. The read model is eventually consistent with the command side.

**Q: How are duplicate API requests handled (idempotency)?**
A: Clients send an `Idempotency-Key` UUID header. The server records it in an
`idempotency_keys` table: new key → process and store the response; `IN_PROGRESS`
→ `409 Conflict`; `COMPLETED` → return the cached response without re-running logic.
Keys expire after 24h and are pruned.

**Q: How are cross-service failures handled?**
A: A **choreographed saga** — each step runs in its local ACID transaction and emits
events. Transient failures retry with exponential backoff + jitter (forward recovery);
non-retryable domain failures (e.g., out of stock) emit a failure event that triggers
idempotent compensating transactions (e.g., order PLACED → CANCELLED).

**Q: How does Kafka preserve ordering, and what delivery semantics apply?**
A: Ordering is per-partition, keyed by aggregate id (same key → same partition).
Delivery is at-least-once, so consumers must be idempotent. Semantics: at-most-once
(can lose), at-least-once (can duplicate), exactly-once (hardest; needs transactions).

**Q: How does the system autoscale on Kubernetes?**
A: Both services run HPAs (`minReplicas: 3`, `maxReplicas: 8`, target 70% CPU / 75%
memory). Pod Disruption Budgets keep ≥2 pods available; topology spread constraints
distribute pods across nodes; rolling updates use `maxUnavailable: 0`, `maxSurge: 1`
for zero downtime.

**Q: How do you protect the system under overload?**
A: Layered defense: HAProxy IP-based rate limiting (stick-table, 429 on breach) +
internal client rate limiting + **adaptive load shedding**. Load shedding watches p90
latency and uses AIMD to cap in-flight concurrency, prioritizing `POST /orders`
(never dropped) over order lookups over degradable catalog search (shed first with
`Retry-After`).

**Q: How are DLQ failures recovered?**
A: Events that fail projection go to a Dead Letter Queue; `order-query` provides
admin/replay tooling to inspect and replay once the issue is fixed.

---

## 6. Monitoring & Observability — Q&A

**Q: How are metrics collected?**
A: Each service exposes `/actuator/prometheus`; Prometheus scrapes it, Grafana
visualizes, Alertmanager routes alerts. Key metrics: `http_server_requests_seconds_*`
(latency/throughput/errors), `outbox_unpublished_records` and
`outbox_unpublished_oldest_age_seconds`, `kafka_consumer_lag`, `events_dlq_total`,
HikariCP pool gauges, and Patroni leader/replication state.

**Q: What SLOs are tracked and alerted?**
A: Critical API p95 < 200ms (catalog, order creation, order query), 5xx error rate,
DLQ arrivals, outbox backlog aging (>5s), Kafka consumer lag (>100), HikariCP
timeouts/leaks, and PostgreSQL HA alerts (no leader, split-brain, missing sync standby).

**Q: How is logging structured?**
A: Logback with two profiles — `local` uses a human-readable console appender;
non-local uses `LogstashEncoder` emitting JSON, with a `service` custom field for
cross-service correlation in aggregation systems.

**Q: Is distributed tracing implemented?**
A: Not full OpenTelemetry — the system relies on correlation IDs propagated through
requests and events, surfaced as JSON log fields, enabling request tracing across services.

**Q: Walk me through debugging an outbox backlog.**
A: Alert fires (`OutboxRelayBacklogAging`/`KafkaConsumerLagHigh`) → query
`outbox_events WHERE published_at IS NULL` for count/age → check the relay polling
thread → verify Kafka reachability via actuator health → remediate (restart Kafka,
watch drain rate, prune old published rows). Runbooks are linked from alert annotations.

---

## 7. Database — Q&A

**Q: Explain ACID.**
A: Atomicity (all-or-nothing), Consistency (valid state before/after), Isolation
(concurrent txns don't interfere), Durability (committed data survives crashes).

**Q: Isolation levels and the anomalies they prevent?**
A: READ_UNCOMMITTED allows dirty/non-repeatable/phantom reads; READ_COMMITTED
prevents dirty reads; REPEATABLE_READ also prevents non-repeatable reads;
SERIALIZABLE prevents all including write skew (at the cost of throughput and
possible serialization failures needing app retries).

**Q: How do indexes work and what are the trade-offs?**
A: Indexes are on-disk B-tree structures turning O(n) scans into O(log n) lookups.
Trade-offs: slower writes (tree insert/rebalance), extra storage, low selectivity on
boolean/status columns, and wrong composite column order hurting usage. Types used:
unique, composite, partial, covering indexes.

**Q: How is the DB made highly available here?**
A: PostgreSQL runs as a Patroni-managed HA cluster with etcd for leader election,
synchronous standby replicas, and `order-query` supports primary/replica datasource
routing with replica-lag monitoring.

**Q: How is connection pool exhaustion handled?**
A: HikariCP is monitored for active/idle/pending connections, timeouts, and leaks.
The runbook covers diagnosing via `pg_stat_activity`/`pg_locks`, terminating rogue
transactions, and tuning `maximum-pool-size` vs Postgres `max_connections`.

---

## 8. Likely Deep-Dive / Follow-up Questions
- Why `FOR UPDATE SKIP LOCKED` over advisory locks or a single leader relay?
- Trade-offs of choreographed saga vs orchestrated saga.
- How do you guarantee exactly-once *effect* on top of at-least-once delivery?
- What happens if Redis is down — does the request fail or fall back to L1/DB?
- How do you prevent cache invalidation races between projection and reader?
- HPA on CPU/memory vs custom metrics (e.g., request latency or Kafka lag) — which is better here?
- How do you handle schema evolution of Kafka event contracts without breaking consumers?
