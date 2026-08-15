# Phase 08 — Evidence-Guided Capacity Tuning Report

- **Date (UTC):** 2026-08-15
- **Phase:** `08 — Load Engineering`
- **Task:** `P8-05 — Evidence-guided capacity tuning`
- **Status:** **PASS / CAPACITY TUNED & VERIFIED**

---

## 1. Executive Summary

Following the baseline bottleneck identification in `P8-04`, task `P8-05` executed targeted, evidence-guided capacity tuning to alleviate the primary outbox relay throughput bottleneck.

By updating the transactional outbox relay in `app` with:
1. Continuous batch draining loop (`hasMore && dueEvents.size >= claimLimit`),
2. Polling interval reduction from `1000ms` to `100ms`,
3. Batch claim limit increase from `100` to `500`, and
4. HikariCP connection pool expansion from `20` to `30` connections,

Outbox relay throughput under heavy write load improved from **51.1 events/sec** to **110.1+ events/sec** (**>2.15x throughput increase**), and outbox backlog at the conclusion of sustained 2,000 VU write load dropped from **11,902 unpublished events down to 722** (a **93.9% backlog reduction**). Under the representative mixed workload contract, end-to-end projection lag remained at **$871\text{ µs}$ average / $1.50\text{ ms}$ p95** with **100% data reconciliation across all schemas**.

---

## 2. Targeted Bottlenecks & Tuning Changes

### Change 1: Transactional Outbox Relay Loop & Polling Frequency
- **Baseline Symptom:** Under 2,000 VU write load (accepting ~90 writes/sec), the outbox accumulated 11,902 unpublished events over 5 minutes because the relay executed strictly once every 1,000ms on a single thread.
- **Causal Evidence:** Single-threaded `@Scheduled(fixedDelay = 1000)` with `batchSize = 100` imposed an artificial theoretical maximum rate of 100 events/sec.
- **Code & Configuration Changes:**
  - `OutboxRelay.kt`: Refactored `publishDueEvents()` with a continuous drain loop that immediately claims the next batch if the current batch is full (`dueEvents.size >= claimLimit`).
  - `OutboxProperties.kt` & `application.yml`: Set `relay-interval-ms = 100` (down from 1,000ms) and `claim-limit = 500` (up from 100).
- **Tradeoff / Operational Impact:** Slightly higher database query frequency when backlog exists, bounded by `claim-limit = 500`.

### Change 2: HikariCP Connection Pool Headroom
- **Baseline Symptom:** Higher concurrency and larger outbox batches increase parallel database demand between HTTP workers and outbox publishing transactions.
- **Configuration Changes:**
  - `app/src/main/resources/application.yml`: Increased `maximum-pool-size` from `20` to `30`, `minimum-idle` from `5` to `10`.
  - `order-query/src/main/resources/orderquery.yml`: Increased `maximum-pool-size` from `20` to `30`, `minimum-idle` from `5` to `10`.
- **Tradeoff / Operational Impact:** Additional ~20 connections on PostgreSQL instance, well within PostgreSQL's default `max_connections = 100` budget.

---

## 3. Comparative Before/After Performance Evidence

### Write-Heavy Workload (100% POST Orders, 2,000 Peak VUs)

| Metric | Before Tuning (P8-04 Baseline) | After Tuning (P8-05) | Delta / Improvement |
|---|---|---|---|
| **Orders Accepted via HTTP** | 27,237 | **33,764** | **+24.0%** higher ingress capacity |
| **HTTP Request Rate** | 1,409.3 RPS | 1,273.4 RPS | Stable |
| **POST /orders Latency (p95)** | 5.22 ms | **3.38 ms** | **35.2% latency reduction** |
| **Events Relayed During Test** | 15,335 | **33,042** | **+115.5% (>2.15x higher throughput)** |
| **Relay Throughput Rate** | ~51.1 events/sec | **~110.1 events/sec** | **+115.5% throughput** |
| **Unpublished Outbox Backlog at End** | 11,902 events | **722 events** | **-93.9% backlog reduction** |
| **Post-Drain Business Integrity** | 100% (27,237/27,237) | **100% (33,764/33,764)** | Zero data loss, zero duplicates |

### Representative Mixed Workload (80/10/10 Contract)

| Metric | Measured Value | SLO Target | Status |
|---|---|---|---|
| **Critical API Duration (p95)** | **2.03 ms** | < 200 ms | **PASS** (99x headroom) |
| **Critical API Duration (p99)** | **3.91 ms** | < 500 ms | **PASS** |
| **HTTP Error Rate (`http_req_failed`)** | **0.00%** | < 0.1% | **PASS** |
| **Order Projection Lag (avg)** | **871 µs** | < 2,000 ms | **PASS** (2300x headroom) |
| **Order Projection Lag (p95)** | **1.50 ms** | < 2,000 ms | **PASS** |
| **Data Reconciliation** | **100% matched** | 100% | **PASS** |

---

## 4. Verification & Regression Testing

1. **Unit & Integration Test Suite (`make verify`):**
   - 40 actionable Gradle tasks executed and **100% PASSED** in 4m 57s.
   - Verified that `OutboxRelayIntegrationTest`, `KafkaSmokeTest`, `OrderFlowIntegrationTest`, `InventoryConsumerIntegrationTest`, `InventoryFailureTest` (DLQ), `KafkaOutageIntegrationTest`, `PartialOutageIntegrationTest`, and `PostgresOutageIntegrationTest` all continue to pass cleanly.
2. **Smoke Test (`make load-smoke`):**
   - 100% checks passed (`250/250`), `p95 = 245 ms` (first cold start), `projection_success = 100%`, and cross-schema data reconciliation passed.
3. **Architecture & Clean Code Compliance:**
   - Detekt static analysis passed with zero warnings.
   - Spotless code formatting passed across all modules.

---

## 5. Architectural Compliance & Constraints

- **No Future Technology Leakage:** No Redis, Elasticsearch, Kubernetes, or APM daemon introduced.
- **Persistence Boundaries Preserved:** PostgreSQL remains the single source of truth with separate schema ownership (`catalog`, `order`, `inventory`, `order_query`).
- **Guaranteed At-Least-Once Delivery & Idempotency:** Maintained without weakening correlation tracking or distributed trace context propagation.
