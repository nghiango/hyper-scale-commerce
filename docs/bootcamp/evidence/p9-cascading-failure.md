# Phase 9 Evidence — Cascading-Failure and Resource-Isolation Qualification

Date: 2026-08-16  
Status: **VERIFIED & PASSED**  
Harness: Toxiproxy (`ghcr.io/shopify/toxiproxy:2.11.0`), k6 (`grafana/k6:0.57.0`), Docker Compose Chaos Overlay (`performance/compose.chaos.yml`)

---

## 1. Objective & Scope

Empirically qualify the fault containment boundaries of the HyperScale Commerce platform across all asynchronous event paths, database connection pools, thread pools, and multi-deployable architectures. Prove that localized degradation or component failure cannot cascade into unrelated system paths.

---

## 2. Resource Isolation & Containment Boundary Matrix

| Fault Domain | Tested Path | Degraded Component | Unrelated / Isolated Path | Observed Outcome on Isolated Path | Containment Status |
|---|---|---|---|---|---|
| **Kafka Broker Outage** | Asynchronous Event Log | `kafka` proxy disabled for 20s | Catalog Browsing & Order Placement (`app`) | Critical API p95: **16.98 ms**; 0.00% errors; 100% orders committed to outbox | **PASSED** (Full Isolation) |
| **Kafka Packet Degradation** | Network Frame Delivery | `kafka` proxy TCP slicing & latency | Catalog Browsing & Order Placement (`app`) | Critical API p95: **20.23 ms**; 0.00% errors | **PASSED** (Full Isolation) |
| **Order-Query DB Outage** | Query Read Database Path | `orderquery_postgres` proxy disabled for 15s | Catalog & Order Commands (`app`) | Critical API p95: **19.80 ms**; 0.00% errors; `app` HikariCP pool unaffected | **PASSED** (Pool Isolation) |
| **Shared DB Outage** | Physical Database Path | All DB proxies disabled for 15s | Application Process & JVM Health | Fail-fast within 5s timeout budget; zero thread starvation; zero JVM memory leaks | **PASSED** (Bounded Failure) |
| **Poison Message Injection** | Message Parsing & Schema | Non-retryable corrupt payloads across all 3 Kafka partitions | Healthy Order Traffic on same & adjacent partitions | Direct DLQ isolation; zero head-of-line blocking; Critical API p95: **18.69 ms** | **PASSED** (DLQ Isolation) |
| **Application Process Crash** | Deployable Process Space | `hyperscale-app` SIGKILL (10s) | Order Query API (`order-query`) | `GET /orders` served throughout outage ($\text{med} = 15.49\text{ ms}$, 100% success) | **PASSED** (Process Isolation) |
| **Order-Query Process Crash** | Deployable Process Space | `hyperscale-order-query` SIGKILL (10s) | Catalog & Order Commands (`app`) | `POST /orders` $\text{p95} = 17.37\text{ ms}$; outbox events accumulated safely | **PASSED** (Process Isolation) |

---

## 3. Deep-Dive Failure Containment Analyses

### 3.1. Catalog Independence from Kafka Log Availability
- **Hypothesis:** Because Catalog browsing reads from PostgreSQL `catalog` tables and does not produce or consume Kafka events, broker latency, packet corruption, or total broker outages will have zero impact on catalog latency.
- **Evidence:** Under 50 concurrent VUs, catalog list product duration median was **6.64 ms** during normal operations, **5.71 ms** during broker latency injection, and **8.60 ms** during total broker cut. Critical API p95 remained sub-25ms throughout (well below the 200ms SLO).

### 3.2. Order Creation Resilience via Transactional Outbox
- **Hypothesis:** Order creation (`POST /orders`) is decoupled from Kafka broker availability via the Transactional Outbox pattern. Orders commit synchronously to PostgreSQL (`order` and `outbox_events` tables), allowing write operations to succeed even during broker outages.
- **Evidence:** During the 20-second complete Kafka outage (`kafka-cut`), 50 orders were placed with **0.00% error rate** and median latency of **15.32 ms**. Upon broker restoration, the outbox relay drained all 50 events in under 2 seconds.

### 3.3. Database Connection Pool Isolation between Deployables
- **Hypothesis:** Independent HikariCP connection pools in `app` and `order-query` communicate via dedicated proxy paths (`app_postgres:5432` and `orderquery_postgres:5433`). A severed connection or connection starvation in `order-query` will not consume connections or block threads in `app`.
- **Evidence:** Disabling `orderquery_postgres` for 15s caused `order-query` requests to fail boundedly, while `app` maintained a critical API p95 of **19.80 ms** and successfully processed 100% of incoming catalog and order requests.

### 3.4. Thread Pool & Memory Boundedness during Database Outages
- **Hypothesis:** Total database unavailability will trigger HikariCP connection acquisition timeouts (5,000ms), returning HTTP 500 error responses and releasing Tomcat worker threads rather than holding threads open indefinitely or causing JVM OutOfMemory errors.
- **Evidence:** During total database cut (`postgres-cut`), requests failed fast within the 5s budget, Tomcat worker thread count remained bounded ($\le 200$), and JVM heap usage remained stable at $<25\%$ of maximum allocated memory.

### 3.5. Partition Head-of-Line Blocking Prevention via Direct DLQ Routing
- **Hypothesis:** Non-retryable poison records (unclosed JSON, corrupted byte streams) will be recognized by `DefaultErrorHandler` and routed immediately to `order-placed-dlq` on delivery attempt 1, preventing exponential retry loops from blocking valid messages on the same partition.
- **Evidence:** In `poison-dlq`, 3 distinct poison payloads injected across partitions 0, 1, and 2 were routed to DLQ immediately ($3 \times 2 = 6$ records in DLQ across both consumer groups). Subsequent valid orders processed with sub-millisecond projection lag ($\text{avg} = 531\text{ \mu s}$) and 100% data reconciliation.

---

## 4. Conclusion & ADR Status

- **Containment Verdict:** **PASS (100% of tested failure boundaries held strictly).**
- **SLO Compliance:** All isolated paths retained Phase 8 SLOs (sub-200ms p95, $\ge 99.9\%$ availability on surviving services).
- **Data Integrity:** Zero ghost orders, zero uncommitted outbox rows, zero inventory over-allocations, zero DLQ spillage.
- **Remediation ADR:** No ADR-0016 remediation proposal is required because existing architectural boundaries (Transactional Outbox, HikariCP pool isolation, explicit timeouts, and non-retryable exception classification) completely contained all injected failure modes.
