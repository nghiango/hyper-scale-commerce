# ADR-0015: Chaos Engineering, Retry, and Fault-Injection Strategy

- Status: Accepted
- Date: 2026-08-15
- Phase: 9 — Chaos Engineering & Distributed Fault Tolerance

## Context

`docs/constitution.md` mandates that the HyperScale Commerce platform evolve into a resilient cloud-native distributed system capable of absorbing severe faults, preventing cascading failures, recovering automatically, and guaranteeing zero intentional data loss.

Following Phase 8 (Load Engineering), the platform is verified to sustain 10,000 concurrent virtual users ($5,700+\text{ RPS}$) and absorb 5x traffic surges under nominal conditions across two containerized deployables (`app` on port 8080 and `order-query` on port 8081), communicating asynchronously via an Apache Kafka broker (`order-placed` topic with 3 partitions and a shared DLQ `order-placed-dlq`) and persisting state across isolated PostgreSQL schemas (`catalog`, `order`, `inventory`, and `order_query`).

However, real-world cloud and distributed operating environments inevitably suffer from:
1. **Network Degradation:** Increased packet latency, jitter, bandwidth throttling, and dropped TCP connections.
2. **Broker Unreachability & Partition Stalls:** Temporary broker partitions, slow metadata synchronization, or socket cuts during high-concurrency event publishing.
3. **Database Resource Contention:** Transient connection starvation, lock wait timeouts, and brief restart outages.
4. **Poison Messages:** Schema mismatches, corrupted payloads, or unparseable JSON records arriving amidst high-volume concurrent streams.
5. **Process & Container Terminations:** Sudden ungraceful container crashes (`SIGKILL`), causing abrupt socket termination and consumer group rebalances.

Current limitations in the repository:
- Kafka consumer retry configurations currently use a fixed retry interval (`FixedBackOff(1000, 3)`), directly conflicting with Constitution Section 5 ("retries must be bounded and use exponential backoff with jitter") and creating herd-retry synchronization under concurrent failures.
- Failure classification between retryable (transient network/DB drop) and non-retryable (poison payload / schema incompatibility) is not explicitly formalized in configuration.
- Existing resilience integration tests (Phase 6) were executed on idle or low-traffic single-thread JVM test fixtures, leaving recovery behavior under sustained 10,000-VU load unverified.
- Direct Docker Compose container networking bypasses any prospective proxy unless an explicit network fault-injection topology is introduced.

Phase 9 establishes the architectural strategy for safe, automated, blast-radius-controlled chaos engineering and constitutional retry compliance.

---

## Alternatives Considered

1. **Toxiproxy via pinned container image with explicit Docker Compose overlay (Chosen).**
   Shopify's Toxiproxy is a lightweight, proven TCP proxy framework designed specifically for simulating network faults (latency, jitter, bandwidth limits, timeouts, connection cuts/slicing). It operates as a separate test-only container, exposes a simple REST API, supports programmatic manipulation via shell/curl/Go, introduces zero runtime dependencies into application code, and can be cleanly integrated into an isolated Compose test overlay.

2. **Chaos Mesh / LitmusChaos.**
   Kubernetes-native chaos orchestration platforms. Rejected: The current architecture runs on Docker Compose; Kubernetes is explicitly forbidden until later infrastructure phases.

3. **Chaos Monkey / Netflix Simian Army.**
   Legacy cloud instance terminators. Rejected: Designed for large AWS EC2 fleets with autoscaling groups; unsuitable for container-level network toxic injection and granular TCP manipulation.

4. **Linux kernel `tc` (traffic control) / `netem` / eBPF chaos.**
   Host-level network packet manipulation. Rejected: Modifies the host operating system network namespace, introduces elevated privilege requirements (`sudo` / `CAP_NET_ADMIN`), is non-portable across developer environments (e.g., macOS Darwin vs Linux hosts), and poses severe blast-radius risks to non-target host services.

5. **In-JVM Application-Level Fault Interceptors (e.g., custom Spring Filters / AspectJ fault injection).**
   Injecting faults inside Java/Kotlin application code via configuration flags. Rejected: Contaminates production source code with synthetic test branching, fails to exercise real OS TCP socket termination, connection pool reconnection logic, or Kafka network layer handling, and hides real OS/network failure dynamics.

---

## Decision

Adopt **Toxiproxy** running as a pinned container in a dedicated Docker Compose chaos overlay (`performance/compose.chaos.yml`) and establish the following architectural policies:

### 1. Test-Plane Topology & Proxy Routing
- **Pinned Container Image:** Use the fixed official Toxiproxy image:
  `ghcr.io/shopify/toxiproxy:2.11.0@sha256:720fa2e8964d7df6db09b62678da47f9adac9ddae8b9e6027de7ee0d1e57c6b7`
- **Isolated Proxy Paths:** Separate proxy endpoints are established for each distinct dependency link to ensure fault isolation:
  - `toxiproxy:5432` $\to$ `postgres:5432` (App Database Path)
  - `toxiproxy:5433` $\to$ `postgres:5432` (Order-Query Database Path)
  - `toxiproxy:9092` $\to$ `kafka:9092` (Kafka Broker Path)
- **Kafka Advertised Listener Integrity:** To prevent Kafka client metadata discovery from bypassing the proxy, the Kafka container in the chaos overlay advertises the proxy hostname (`toxiproxy:9092`), ensuring that all subsequent consumer and producer TCP connections continue through the fault-injection proxy.
- **Zero Production Runtime Contamination:** No Toxiproxy client libraries or test hooks are added to production application modules (`app`, `order-query`, `contracts`).

### 2. Constitutional Kafka Consumer Retry Policy
To satisfy Constitution Section 5 and Section 7, replace all legacy fixed retry policies in `app` (Inventory consumer) and `order-query` (Read model projection consumer) with an explicit, bounded exponential-backoff-with-jitter schedule:

| Parameter | Value | Rationale |
|---|---|---|
| **Max Retry Attempts** | **3** (total 4 deliveries including initial) | Limits thread blocking and prevent unbounded consumer lag. |
| **Initial Backoff Interval** | **200 ms** | Fast retry for transient network blips. |
| **Multiplier** | **2.0** | Exponential spacing between retry attempts. |
| **Max Backoff Interval** | **2,000 ms (2.0s)** | Caps maximum delay before dead-lettering. |
| **Jitter Factor** | **0.5 (±50%)** | De-synchronizes concurrent consumers to prevent thundering herds on recovery. |
| **Dead-Letter Recovery** | `DeadLetterPublishingRecoverer` | Routes exhausted or non-retryable messages to `order-placed-dlq`. |

#### Failure Classification
- **Retryable Exceptions:** Transient infrastructure faults (e.g., `SQLException`, `DataAccessException`, Hikari `SQLTransientConnectionException`, network timeouts). These trigger exponential backoff.
- **Non-Retryable Exceptions:** Permanent business or data corruption faults (e.g., `JacksonException`, `JsonParseException`, `IllegalArgumentException`, schema/validation errors). These bypass retries and route directly to the DLQ on the first attempt, preventing partition stalling.

### 3. Fault Models & Scenario Hypotheses

| Scenario | Injected Fault | Steady-State / Recovery Hypothesis | Invariant / Zero-Loss Requirement |
|---|---|---|---|
| **Network Latency & Jitter** | Upstream latency ($+200\text{ms}$ to $+500\text{ms}$ with jitter) on Kafka or PostgreSQL proxy paths under 10k load. | Critical APIs remain responsive within bounded timeout budgets ($< 1.0\text{s}$); latency normalizes to $\text{p95} < 200\text{ms}$ within 30s post-recovery. | 100% data reconciliation; zero dropped writes; zero connection leaks. |
| **Kafka Broker Partition / Outage** | Disable Kafka proxy (or stop broker) for 60 seconds during active order creation. | `POST /orders` continues succeeding; `app` outbox table buffers events; upon broker recovery, relay drains backlog continuously. | $\text{orders} = \text{outbox} = \text{reservations} = \text{read\_model}$; 0 DLQ residue; 0 unpublished outbox. |
| **Database Connection Contention** | Introduce connection latency and pool starvation on PostgreSQL proxy. | App handles connection timeouts gracefully with standard 503/500 responses; no corrupted in-flight transactions. | Committed orders remain durable; rollback on uncommitted writes; automatic pool recovery. |
| **Concurrent Poison Messages** | Inject malformed JSON and invalid schema payloads across all 3 Kafka partitions under active load. | Non-retryable poison messages route immediately to DLQ; healthy messages on same and adjacent partitions process without head-of-line blocking. | All healthy orders projected; exactly $N$ poison messages in DLQ; `events_dlq_total` metric incremented. |
| **Process Crash & SIGKILL** | Send abrupt `SIGKILL` to `app` or `order-query` container during steady-state load. | Explicit harness restart restarts service; Kafka consumer group rebalances; in-flight uncommitted offset is redelivered and deduplicated. | Zero duplicate inventory reservations; zero duplicate read-model rows; zero lost committed orders. |
| **Cascading Failure & Isolation** | Heavily degrade `order-query` while maintaining high Catalog browse traffic. | Catalog reads (`GET /catalog/products`) remain fast ($\text{p95} < 10\text{ms}$); `app` thread pool does not starve. | Bulkhead isolation between monolith commands and query services verified. |

### 4. Blast-Radius, Safety Controls & Automated Cleanup
- **Target Allow-Listing:** Chaos scripts explicitly validate container names and network aliases before issuing toxic or process commands.
- **Signal Traps & Watchdogs:** All chaos orchestration scripts register POSIX signal traps (`EXIT`, `INT`, `TERM`) ensuring Toxiproxy toxics are deleted and proxies reset to pass-through even if tests are forcefully interrupted.
- **Timeouts:** A hard watchdog timer (300 seconds) forcibly aborts any hung chaos run and initiates cleanup.
- **Topology Realism:** Explicitly document that current single-broker Kafka cannot perform broker failover or multi-broker leader election. Claims of broker failover are strictly rejected until multi-broker clusters are approved.

---

## Consequences

### Positive
- **Verifiable Reliability:** Proves that resilience mechanisms (outbox, idempotent consumers, DLQs, connection pools) operate correctly under high concurrent traffic and severe network faults.
- **Constitutional Alignment:** Enforces exponential backoff with jitter and explicit failure classification across all event consumers.
- **Safety & Portability:** Black-box proxy injection via Toxiproxy isolates network faults to container bridges without requiring host OS kernel modifications or root permissions.
- **Traceable Observability:** Injected failures, retries, and DLQ routings are observable through structured logs and Prometheus metric counters.

### Negative / Tradeoffs
- **Additional Test Infrastructure:** Requires maintaining `performance/compose.chaos.yml` and proxy mapping scripts.
- **Execution Time:** Full chaos qualification scenarios require deliberate fault injection windows (30–60s) and recovery observation windows (30–60s), making them suitable for dedicated verification (`make chaos-*`) rather than instant CI unit builds.

---

## Failure Modes & Mitigations

| Failure Mode | Prevention / Mitigation |
|---|---|
| Toxiproxy crashes or remains in degraded state after test | Preflight verification tests proxy connectivity and resets all toxics before every run. Signal traps ensure cleanup on script exit. |
| Kafka client bypasses proxy after bootstrap | Kafka container advertises `toxiproxy:9092` in the chaos Compose overlay. |
| Consumer enters infinite retry loop on malformed JSON | `JacksonException` and parsing errors are classified as non-retryable and routed directly to DLQ. |
| Host resource exhaustion during cascading chaos | Hard timeouts and capped VU counts in load scripts prevent runaway resource consumption. |

---

## References
- `AGENTS.md`
- `docs/constitution.md` (§5 Distributed Systems Rules, §7 Reliability Rules)
- `docs/adr/0006-kafka-event-broker.md`
- `docs/adr/0007-transactional-outbox.md`
- `docs/adr/0012-resilience-strategy.md`
- `docs/adr/0014-load-test-strategy.md`
- `docs/bootcamp/phase-09-plan.md`
