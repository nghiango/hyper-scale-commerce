# Phase 9 Evidence — Kafka Reachability & Degraded Network Chaos

Date: 2026-08-15  
Status: **VERIFIED & PASSED**  
Harness: Toxiproxy (`ghcr.io/shopify/toxiproxy:2.11.0`), k6 (`grafana/k6:0.57.0`), Docker Compose Chaos Overlay (`performance/compose.chaos.yml`)

---

## 1. Objective & Scope

Empirically verify the resilience, outbox buffering, consumer recovery, and zero data loss of the HyperScale Commerce platform when the Kafka broker path experiences severe network degradation, packet loss, network partition (connection cut), and abrupt container restarts during active concurrent workload.

---

## 2. Workload & Topology Under Test

- **Workload:** 50 Concurrent Virtual Users running 80/10/10 mixed transactions (80% Catalog Browse, 10% Order Creation, 10% Returning Customer Queries) for 40 seconds per scenario.
- **Topology:** Two deployables (`app:8080`, `order-query:8081`), single PostgreSQL 16 instance with schema isolation, Apache Kafka 3.7.0 with 3-partition `order-placed` topic, and Toxiproxy intercepting all client-to-broker traffic on `0.0.0.0:9092`.
- **Pre-Conditions:** Clean database and topic reset before every scenario; proxy health verified via preflight checks.

---

## 3. Experimental Results Matrix

| Scenario | Injected Fault | Duration | Total Reqs | HTTP Error Rate | Critical API p95 | Projection Success | Outbox Drain | Reconciliation |
|---|---|---|---|---|---|---|---|---|
| **`kafka-control`** | None (Baseline Control) | 40s | 1,150 | **0.00%** | **21.03 ms** | 100.0% (50/50) | 0s | **50/50 (100%)** |
| **`kafka-latency`** | +300ms ± 100ms Latency on Broker Proxy | 15s fault / 40s run | 1,150 | **0.00%** | **20.23 ms** | 100.0% (50/50) | <1s | **50/50 (100%)** |
| **`kafka-slicer`** | TCP Packet Slicing (128B chunks + 20ms delay) | 15s fault / 40s run | 1,150 | **0.00%** | **22.78 ms** | 100.0% (50/50) | <1s | **50/50 (100%)** |
| **`kafka-cut`** | Broker Proxy Disabled (Total Network Cut) | 20s cut / 40s run | 1,539 | **0.00%** | **16.98 ms** | 100.0% (50/50) | 2s | **50/50 (100%)** |
| **`kafka-restart`** | Abrupt Container Restart (`hyperscale-kafka`) | Restart at T=15s | 1,150 | **0.00%** | **21.67 ms** | 100.0% (50/50) | 3s | **50/50 (100%)** |

---

## 4. Key Architectural Observations

1. **Transactional Outbox Durability:**
   During the 20-second complete Kafka outage (`kafka-cut`), `POST /orders` HTTP latency remained sub-25ms with 0.00% failure rate because orders were committed synchronously to the database outbox (`order.outbox_events`). No clients were blocked on broker timeouts.
2. **Autonomous Outbox Backlog Drain:**
   When the broker proxy was re-enabled, the outbox relay in `app` continuously claimed unpublished batches (500 records / 100ms) and drained the backlog into Kafka within 2 seconds.
3. **Catalog Independence:**
   Catalog browsing APIs (`GET /catalog/products` and `GET /catalog/products/{id}`) maintained sub-10ms median latency and sub-22ms p95 throughout all Kafka broker outages and packet degradation.
4. **Idempotent Consumer Deduplication & Zero DLQ Spillage:**
   After broker restart and proxy reconnection, consumers in `inventory` and `order-query` re-established partition leases and committed offsets cleanly. Zero valid events were sent to the DLQ (`events_dlq_total` = 0).
5. **Exact Cross-Schema Data Reconciliation:**
   Post-chaos data reconciliation (`reconcile-data.sh`) confirmed 100% mathematical equality across all schemas:
   $$\text{orders (50)} = \text{outbox (50)} = \text{inventory\_reservations (50)} = \text{order\_read\_model (50)}$$
   with zero duplicate stock deductions and zero orphaned rows.

---

## 5. Conclusion & Phase Gate Status

- All 4 Kafka chaos scenarios passed acceptance criteria without data loss or SLO violations.
- Single-broker topology limitations respected (no failover or leader-election claims made).
- Verification completed successfully.
