# Phase 9 Evidence — Chaos Engineering, Resilience & Fault Injection Comprehensive Report

Date: 2026-08-16  
Status: **VERIFIED & PASSED**  
Approved ADR: [ADR-0015: Chaos Engineering, Retry, and Fault-Injection Strategy](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/docs/adr/0015-chaos-engineering-strategy.md)  
Harness: Toxiproxy (`ghcr.io/shopify/toxiproxy:2.11.0`), k6 (`grafana/k6:0.57.0`), Docker Compose Chaos Overlay (`performance/compose.chaos.yml`)

---

## 1. Executive Summary & Phase Gate Status

In Phase 9, HyperScale Commerce subjected the distributed, event-driven architecture built across Phases 1–8 to systematic, empirical chaos experiments under sustained 50-VU concurrent mixed load. 

All core resilience invariants were empirically confirmed:
- **Zero Data Loss & Exact Cross-Schema Reconciliation:** In every qualifying chaos run, 100% of successfully committed orders were reconciled across `order`, `outbox_events`, `inventory_reservations`, and `order_read_model`.
- **Decoupled Write Path Durability:** Complete Kafka broker outages (20s partition cut) caused 0 HTTP failures on `POST /orders` due to the Transactional Outbox pattern.
- **Service & Pool Isolation:** Severing database connections or crashing `order-query` had zero impact on `app` catalog browsing or order placement latency.
- **Bounded Non-Retryable Poison Isolation:** Malformed and corrupted payloads across all 3 Kafka partitions routed immediately to the shared DLQ without head-of-line blocking on adjacent healthy messages.
- **Fail-Fast Error Budgets:** Total database outages failed fast within the 5s acquisition timeout without thread pool exhaustion or JVM heap memory leaks.

---

## 2. Evidence Artifact Index

| Task ID | Focus Area | Evidence Document | Status |
|---|---|---|---|
| **P9-01** | Chaos Strategy & Safety Protocol | [`docs/adr/0015-chaos-engineering-strategy.md`](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/docs/adr/0015-chaos-engineering-strategy.md) | **APPROVED** |
| **P9-02** | Exponential Backoff with Jitter & DLQ Routing | Code & Unit Tests in `app` & `order-query` | **VERIFIED** |
| **P9-03** | Safe Chaos Harness & Proxy Routing | [`performance/compose.chaos.yml`](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/performance/compose.chaos.yml) & Scripts | **VERIFIED** |
| **P9-04** | Kafka Reachability & Network Degradation | [`docs/bootcamp/evidence/p9-kafka-network.md`](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/docs/bootcamp/evidence/p9-kafka-network.md) | **PASSED** |
| **P9-05** | PostgreSQL Path Degradation & Outages | [`docs/bootcamp/evidence/p9-postgres.md`](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/docs/bootcamp/evidence/p9-postgres.md) | **PASSED** |
| **P9-06** | Concurrent Poison Message & DLQ Isolation | [`docs/bootcamp/evidence/p9-poison-dlq.md`](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/docs/bootcamp/evidence/p9-poison-dlq.md) | **PASSED** |
| **P9-07** | Application Process Crash & Restoration | [`docs/bootcamp/evidence/p9-process-crash.md`](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/docs/bootcamp/evidence/p9-process-crash.md) | **PASSED** |
| **P9-08** | Cascading-Failure & Resource Isolation | [`docs/bootcamp/evidence/p9-cascading-failure.md`](file:///Users/ngng/working/projects/experiments/hyper-scale-commerce/docs/bootcamp/evidence/p9-cascading-failure.md) | **PASSED** |

---

## 3. Consolidated Chaos Matrix

| Scenario Group | Scenario Name | Fault Injected | Duration | Total Reqs | HTTP Error Rate | Critical API p95 | DLQ Isolation | Data Reconciliation |
|---|---|---|---|---|---|---|---|---|
| **Control** | `kafka-control` | Baseline (No Faults) | 40s | 1,150 | **0.00%** | **21.03 ms** | 0 msgs | **50/50 (100%)** |
| **Kafka Chaos** | `kafka-latency` | +300ms ± 100ms Latency on Broker Proxy | 15s fault | 1,150 | **0.00%** | **20.23 ms** | 0 msgs | **50/50 (100%)** |
| **Kafka Chaos** | `kafka-slicer` | TCP Packet Slicing (128B + 20ms) | 15s fault | 1,150 | **0.00%** | **22.78 ms** | 0 msgs | **50/50 (100%)** |
| **Kafka Chaos** | `kafka-cut` | Total Broker Proxy Disconnect | 20s cut | 1,539 | **0.00%** | **16.98 ms** | 0 msgs | **50/50 (100%)** |
| **Kafka Chaos** | `kafka-restart` | Broker Container Restart | T=15s | 1,150 | **0.00%** | **21.67 ms** | 0 msgs | **50/50 (100%)** |
| **PostgreSQL Chaos** | `postgres-app-latency` | +200ms ± 50ms Latency on `app_postgres` | 15s fault | 1,050 | **0.00%** | **857.95 ms** (bounded) | 0 msgs | **50/50 (100%)** |
| **PostgreSQL Chaos** | `postgres-orderquery-cut`| Disable `orderquery_postgres` Proxy | 15s fault | 1,199 | **0.00%** | **19.80 ms** | 0 msgs | **50/50 (100%)** |
| **PostgreSQL Chaos** | `postgres-cut` | Disable All Database Proxies | 15s fault | 900 | **11.11%** (bounded) | **5.03 s** (timeout budget) | 0 msgs | **50/50 (100%)** |
| **PostgreSQL Chaos** | `postgres-restart` | Database Container Restart | T=15s | 1,150 | **0.00%** | **13.47 ms** | 0 msgs | **50/50 (100%)** |
| **Poison Message** | `poison-dlq` | 3 Corrupt Payloads across all 3 Partitions | T=15s | 1,150 | **0.00%** | **18.69 ms** | **6 msgs (3/consumer)**| **50/50 (100%)** |
| **Process Crash** | `app-crash` | `hyperscale-app` SIGKILL (10s window) | 10s loss | 1,100 | **31.81%** (bounded) | `order-query` med 15.49ms | 0 msgs | **100% (Exact match)** |
| **Process Crash** | `order-query-crash` | `hyperscale-order-query` SIGKILL (10s)| 10s loss | 1,850 | **8.10%** (bounded) | `app` POST p95 17.37ms | 0 msgs | **50/50 (100%)** |

---

## 4. Verification of Safety Harness Controls

1. **Deterministic Cleanup Traps:** All chaos scenario scripts declare traps on `EXIT`, `INT`, and `TERM` executing `cleanup-chaos.sh`, invoking Toxiproxy's `POST /reset` endpoint and ensuring zero residual latency or severed connections remain active after test completion.
2. **Target Allow-List Enforcement:** `validate_target_safety` strictly rejects any container or proxy target not explicitly listed in `ALLOWED_CONTAINERS` (`hyperscale-app`, `hyperscale-order-query`, `hyperscale-postgres`, `hyperscale-kafka`).
3. **Advertised Listener Routing Integrity:** Kafka container advertises `PLAINTEXT://toxiproxy:9092`, ensuring clients never bypass the proxy layer upon receiving Kafka metadata responses.

---

## 5. Architectural Boundaries & Limitations Acknowledged

1. **Single Kafka Broker:** The platform runs a single-broker Kafka deployment in Phase 9. Experiments verified outbox buffering and log durability across broker reachability loss and restarts, making no claims regarding partition leader failover or cluster quorum re-elections.
2. **Single Physical PostgreSQL Instance:** PostgreSQL schemas (`order`, `inventory`, `catalog`, `order_query`) share one physical database instance. Experiments proved connection pool isolation and transaction atomicity, making no claims regarding multi-node replication failover.
3. **Explicit Harness Restoration:** Container crashes (`SIGKILL`) were restored deterministically by explicit harness commands (`docker start`), avoiding false claims of infrastructure self-healing.

---

## 6. Phase Gate Verdict

- **Phase 9 Acceptance Criteria:** **100% SATISFIED**.
- **Automated Verification:** All tests and linters pass (`make verify`, `make load-smoke`, `make chaos-smoke`).
- **Data Integrity:** **Zero data loss across all chaos domains.**
- **Phase 9 is COMPLETE and ready for Phase Review.**
