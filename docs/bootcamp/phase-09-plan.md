# Phase 09 — Chaos Engineering & Distributed Fault Tolerance

Status: **APPROVED**

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-08-plan.md`
- `docs/bootcamp/evidence/p8-load-engineering.md`
- `docs/adr/0006-kafka-event-broker.md`
- `docs/adr/0007-transactional-outbox.md`
- `docs/adr/0008-cqrs-order-query-side.md`
- `docs/adr/0010-extract-order-query-service.md`
- `docs/adr/0011-monorepo-module-data-ownership.md`
- `docs/adr/0012-resilience-strategy.md`
- `docs/adr/0013-observability-strategy.md`
- `docs/adr/0014-load-test-strategy.md`
- The existing Phase 8 implementation (k6 harness, multi-partition topics, batch outbox relay, 10k qualification evidence, 5x spike evidence)

---

## 1. Phase objective

Subject the qualified, 10,000-user capable HyperScale Commerce platform to active, reproducible chaos experiments and automated fault injection—including network degradation, broker partitions, database connection pool starvation, sudden container crashes (`SIGKILL`), poison messages under load, and cascading failure scenarios—to empirically prove that the system degrades gracefully, recovers automatically, and guarantees zero intentional data loss under adverse operating conditions.

---

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. Modular Monolith
3. Performance Engineering
4. Event-Driven Architecture
5. CQRS
6. Service Extraction
7. Resilience Engineering
8. Observability
9. Load Engineering
10. **Chaos Engineering** ← this phase

Phase 8 proved that under nominal and spike operating conditions, the system sustains 10,000 concurrent users, sub-200ms p95 latency, and zero data loss. However, real distributed production systems experience unpredictable runtime failures:
- Network links drop packets or introduce latency spikes.
- Brokers become temporarily unreachable or undergo partition leader elections.
- Database connection pools experience transient exhaustion.
- Services crash abruptly under memory or CPU pressure.
- Unhandled poison messages arrive in concurrent event streams.

The constitution (§7 Reliability Rules) requires that distributed systems prevent cascading failures through bulkheads, circuit breakers, bounded retries, and automated self-healing without corrupting business state. Phase 9 designs, executes, and validates these fault scenarios under active load.

---

## 3. Starting architecture / state

| Component / Layer | State at Start of Phase 9 |
|---|---|
| **Deployables** | Two containerized deployables: `app` (:8080) and `order-query` (:8081) sharing `contracts` module. |
| **Persistence** | PostgreSQL 16 Alpine with isolated schemas: `catalog`, `order`, `inventory` (app) and `order_query` (order-query). |
| **Messaging** | Apache Kafka 3.7.0 (KRaft mode); 3-partition `order-placed` topic; dead-letter queues (`order-placed-dlq`, `order-placed-order-query-dlq`). |
| **Outbox Relay** | Continuous drain loop in `app` with batch `markPublished(Collection<Long>)`, 100ms interval, 500 claim batch limit. |
| **Consumers** | Idempotent consumers in `inventory` (in `app`) and `order-query` with listener concurrency = 3 and bounded retries. |
| **Observability** | W3C trace context propagation, Micrometer Tracing (Brave), SLF4J MDC correlation, structured JSON logging, Prometheus metrics. |
| **Load Harness** | Pinned k6 container (`grafana/k6:0.57.0`), automated test runner `run-scenario.sh`, preflight guards, and Make targets. |
| **Qualified Capacity** | 10,000 concurrent users, 5,700+ steady-state RPS, sub-4ms critical API p95, 5x spike absorption, 100% data reconciliation. |

---

## 4. Target architecture / state

Phase 9 preserves the two-deployable topology while adding a test-only **Fault Injection & Chaos Harness** (utilizing Toxiproxy / Docker network manipulation and chaos scenario runners):

```text
                                  [ k6 Load Generator ]
                               (Active 10k / 5x Workload)
                                           |
                                           v
+-----------------------------------------------------------------------------------+
| Chaos Injection Control Plane (Toxiproxy / Chaos Runner / Network Jitter / SIGKILL) |
+-----------------------------------------------------------------------------------+
           |                                                      |
           v                                                      v
     [ app:8080 ]                                         [ order-query:8081 ]
  (Catalog + Order Writes)                                 (Order Read Model)
           |                                                      ^
           | (Outbox)                                             | (Consumer)
           v                                                      |
    [ PostgreSQL 16 ] <======== (Chaos Injected) =======> [ Apache Kafka 3.7.0 ]
 (Conn drops / restarts)                               (Partitions / Latency / Drops)
```

---

## 5. Problems this phase addresses

1. **Untested Failure Under Concurrent Load:** Existing Phase 6 outage tests were executed on idle or low-traffic Testcontainers, not against sustained 10,000-VU traffic.
2. **Network Degraded State:** No experiments verify system behavior when network latency between `app`, PostgreSQL, and Kafka increases from $< 1\text{ms}$ to $500\text{ms}+$.
3. **Partition Rebalance & Consumer Crashes:** No verification exists for in-flight partition rebalances or container `SIGKILL` terminations during active outbox publication.
4. **Cascading Failure Protection:** Lack of empirical evidence that slow downstream queries or Kafka pauses do not cascade into thread pool starvation on upstream HTTP endpoints.
5. **Concurrent Poison Message Isolation:** Verification that poison messages arriving simultaneously across all 3 Kafka partitions are isolated to DLQs without blocking healthy message processing.

---

## 6. Architecture changes

- **No Product Topology Redesign:** `app`, `order-query`, Kafka, and PostgreSQL remain the core product services.
- **Add Fault Injection Capabilities:** Add test-only chaos injection scripts under `performance/chaos/` (utilizing Toxiproxy and Docker container manipulation).
- **Add Fault-Tolerant Resilience Guards:** Introduce explicit connection pool timeouts, socket timeouts, and circuit breaking/bulkhead policies where necessary.
- **Add Chaos Make Targets:** Add `make chaos-network`, `make chaos-broker`, `make chaos-database`, `make chaos-crash`, `make chaos-all`.

---

## 7. Technology changes

### Allowed Technologies in Phase 9
- Existing Phase 8 stack (Kotlin, Spring Boot, PostgreSQL, Kafka, jOOQ, Flyway, Micrometer Tracing, Brave, k6).
- Toxiproxy (test-only container for network latency, jitter, packet loss, and connection cuts).
- POSIX shell and Docker Engine CLI for container fault injection (`SIGKILL`, pause/unpause, restart).

### Forbidden Technologies in Phase 9
- Kubernetes / Service Mesh (Istio/Linkerd).
- Redis / external distributed caching.
- Elasticsearch.
- Microservice sprawl beyond the approved two deployables.
- Separate physical databases per service.

---

## 8. Non-functional requirements

- **Resilience:** 100% automated recovery upon fault clearance without manual operator intervention.
- **Data Integrity:** Zero lost orders, zero duplicate reservations, and zero unprojected read-model records across all chaos runs.
- **Availability During Partial Failure:** Independent components (e.g., Catalog browsing) must remain operational when asynchronous pipelines or read models experience faults.
- **Safety:** Chaos tools must target only ephemeral test containers and never touch host networks or external environments.

---

## 9. Performance expectations

- **During Chaos Ingestion:** Critical API p95 may degrade during active faults, but must be bounded by explicit timeouts (e.g., HTTP requests fail fast or succeed within configured timeout budgets $< 1.0\text{s}$).
- **Post-Chaos Recovery:** Latency must return to baseline steady-state band ($\text{p95} < 200\text{ms}$) within 60 seconds after fault removal.

---

## 10. Reliability expectations

- **At-Least-Once Delivery:** Outbox relay must buffer events during broker/network failure and resume publishing seamlessly upon reconnection.
- **Idempotent Deduplication:** Repeated message deliveries triggered by consumer restarts must produce zero duplicate side effects.
- **Poison Message Containment:** Malformed events must route to DLQ within bounded retries (default: 3 attempts) without stalling healthy partition processing.

---

## 11. Observability requirements

- Every injected fault must be traceable in Prometheus metrics (`events_dlq_total`, `outbox_relay_lag`, `http_server_requests_seconds`, connection pool active/pending metrics).
- Injected failure logs must include structured error tags, trace IDs, and correlation context.

---

## 12. Security considerations

- Fault injection containers must operate in an isolated bridge network with no external exposure.
- No sensitive credentials or secrets introduced into test scripts or chaos definitions.

---

## 13. Data considerations

- Chaos tests must execute against isolated test data.
- Post-chaos reconciliation must audit cross-schema consistency across `order`, `inventory`, and `order_query`.

---

## 14. Explicitly out-of-scope capabilities

- Multi-datacenter or multi-region replication chaos.
- Physical hardware failure simulation.
- Kernel-level fault injection (e.g., eBPF chaos).
- Third-party SaaS external dependencies.

---

## 15. Dependencies on the previous phase

Phase 9 directly depends on Phase 8:
- Pinned k6 harness and workload definitions from `P8-03`.
- Tuned outbox relay, multi-partition Kafka topics, and connection pools from `P8-05`.
- Baseline capacity numbers and reconciliation scripts from `P8-06` and `P8-07`.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Chaos scripts leave zombie processes or broken networks | Subsequent test runs fail | Preflight cleanup scripts reset Toxiproxy and Docker state before every run. |
| Extreme fault injection overwhelms host machine | Test execution hangs | Strict timeouts and watchdog timers kill runaway scenarios after 5 minutes. |
| In-flight chaos corrupts database seed data | Flaky reconciliation | Database and Kafka topics are freshly truncated and re-seeded before each experiment. |

---

## 17. ADRs that may be required

- **ADR-0015:** Chaos Engineering & Automated Fault Injection Strategy.
- **ADR-0016:** Cascading Failure Prevention & Downstream Degradation Policy (conditional if circuit breaking or bulkheads are modified).

---

## 18. Ordered implementation tasks

```text
P9-01 --> P9-02 --> P9-03 --> P9-04 --> P9-05 --> P9-06 --> P9-07 --> P9-08
```

---

### Task Definitions

#### P9-01 — ADR: Chaos Engineering & Automated Fault Injection Strategy
- **Objective:** Create and approve ADR-0015 defining the chaos engineering framework, fault injection mechanisms, steady-state hypotheses, blast radius controls, and pass/fail criteria.
- **Dependencies:** None.
- **Scope:** Architecture decision record only.
- **Acceptance Criteria:** ADR-0015 accepted; covers network, broker, database, process crash, and poison message fault models.

#### P9-02 — Chaos Harness & Fault Injection Framework
- **Objective:** Build the automated chaos orchestration harness integrating Toxiproxy and Docker fault runners with the existing k6 load engine.
- **Dependencies:** P9-01.
- **Scope:** Test infrastructure, compose overlays (`compose.chaos.yml`), and Make targets.
- **Acceptance Criteria:** `make chaos-smoke` executes a 30-second fault injection test and verifies clean container teardown.

#### P9-03 — Network Partition & Kafka Broker Chaos
- **Objective:** Inject network latency, packet drop, and temporary broker partitions during active 10k/5x load.
- **Dependencies:** P9-02.
- **Scope:** Kafka and network fault experiments.
- **Acceptance Criteria:** `app` buffers events in the outbox during broker outage; drains completely upon broker restoration with 100% data reconciliation and zero lost orders.

#### P9-04 — Database Saturation & Outage Chaos
- **Objective:** Inject PostgreSQL connection starvation, slow queries, and transient container restarts during active load.
- **Dependencies:** P9-02.
- **Scope:** Database fault experiments and resilience verification.
- **Acceptance Criteria:** HTTP write commands fail fast or succeed cleanly; no partial or corrupted transactions; services reconnect automatically upon database recovery.

#### P9-05 — Poison Pill & Concurrent Dead-Letter Queue Isolation
- **Objective:** Inject malformed payloads and invalid schemas across all 3 Kafka partitions concurrently under load.
- **Dependencies:** P9-02.
- **Scope:** DLQ routing and consumer error handling.
- **Acceptance Criteria:** Poison messages are routed to `order-placed-dlq` within 3 retries; healthy messages on adjacent partitions process without delay; DLQ metrics increment accurately.

#### P9-06 — Process & Container Crash Recovery under Concurrency
- **Objective:** Issue sudden `SIGKILL` terminations and abrupt container restarts to `app` and `order-query` during peak load.
- **Dependencies:** P9-02.
- **Scope:** Process crash, restart recovery, and Kafka partition rebalance verification.
- **Acceptance Criteria:** Services restart within 15s; Kafka consumer groups rebalance cleanly; in-flight uncommitted messages are safely redelivered and deduplicated.

#### P9-07 — Cascading Failure Prevention & Backpressure Qualification
- **Objective:** Verify that slow downstream components (e.g., degraded `order-query` read model) do not exhaust upstream `app` thread pools or degrade Catalog browse performance.
- **Dependencies:** P9-03, P9-04, P9-05, P9-06.
- **Scope:** Bulkhead isolation, timeout enforcement, and graceful degradation.
- **Acceptance Criteria:** Catalog browsing maintains sub-10ms p95 while `order-query` is artificially slowed; memory and thread pools remain bounded.

#### P9-08 — Chaos Engineering Evidence & Phase Gate
- **Objective:** Consolidate comprehensive evidence across all chaos experiments, verify reproducibility, and conduct final Phase 9 review.
- **Dependencies:** P9-01 through P9-07.
- **Scope:** Documentation, evidence consolidation, and phase exit verification.
- **Acceptance Criteria:** `docs/bootcamp/evidence/p9-chaos-engineering.md` committed; all chaos experiments reproducible; `make verify` passes cleanly.

---

## 19. Phase exit criteria

Phase 9 is complete only when:

1. Tasks `P9-01` through `P9-08` are implemented and independently verified.
2. ADR-0015 is accepted.
3. Automated fault injection tests for network, broker, database, poison message, and process crash scenarios pass with 100% automated recovery.
4. Deterministic data reconciliation proves zero lost orders and zero duplicate business effects after all chaos experiments.
5. Upstream services prevent cascading failure and maintain independent availability when downstream dependencies fail.
6. `make verify` and `make chaos-smoke` pass cleanly from a fresh checkout.
7. Phase review passes before declaring Phase 9 complete.
