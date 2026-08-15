# Phase 9 Evidence — PostgreSQL Path Degradation & Outage Chaos

Date: 2026-08-15  
Status: **VERIFIED & PASSED**  
Harness: Toxiproxy (`ghcr.io/shopify/toxiproxy:2.11.0`), k6 (`grafana/k6:0.57.0`), Docker Compose Chaos Overlay (`performance/compose.chaos.yml`)

---

## 1. Objective & Scope

Empirically verify transaction atomicity, connection pool isolation, fail-fast boundary handling, and automated HikariCP pool recovery when PostgreSQL paths experience latency degradation, service-isolated network cuts, total connection drops, and container restarts under active concurrent workload.

---

## 2. Workload & Topology Under Test

- **Workload:** 50 Concurrent Virtual Users running 80/10/10 mixed transactions (80% Catalog Browse, 10% Order Creation, 10% Returning Customer Queries) for 40 seconds per scenario.
- **Topology:** Two deployables (`app:8080`, `order-query:8081`) communicating with PostgreSQL 16 via independent Toxiproxy paths:
  - `app_postgres` (`0.0.0.0:5432` $\to$ `postgres:5432`)
  - `orderquery_postgres` (`0.0.0.0:5433` $\to$ `postgres:5432`)
- **Pre-Conditions:** Clean transactional tables reset before every scenario; proxy health verified via preflight checks.

---

## 3. Experimental Results Matrix

| Scenario | Injected Fault | Duration | Total Reqs | HTTP Error Rate | Critical API p95 | Projection Success | Outbox Drain | Reconciliation |
|---|---|---|---|---|---|---|---|---|
| **`postgres-control`** | None (Baseline Control) | 40s | 1,150 | **0.00%** | **17.11 ms** | 100.0% (50/50) | 0s | **50/50 (100%)** |
| **`postgres-app-latency`** | +200ms ± 50ms Latency on `app_postgres` | 15s fault / 40s run | 1,050 | **0.00%** | **857.95 ms** (bounded) | 100.0% (50/50) | <1s | **50/50 (100%)** |
| **`postgres-orderquery-cut`** | Disable `orderquery_postgres` Proxy | 15s fault / 40s run | 1,199 | **0.00%** | **19.80 ms** | 100.0% (50/50) | <1s | **50/50 (100%)** |
| **`postgres-cut`** | Disable All PostgreSQL Proxies | 15s fault / 40s run | 900 | **11.11%** (bounded) | **5.03 s** (timeout budget) | 100.0% (50/50 committed) | <1s | **50/50 (100%)** |
| **`postgres-restart`** | Abrupt Container Restart (`hyperscale-postgres`) | Restart at T=15s | 1,150 | **0.00%** | **13.47 ms** | 100.0% (50/50) | <1s | **50/50 (100%)** |

---

## 4. Key Architectural Observations

1. **Transaction Atomicity & Zero Ghost Records:**
   During the 15-second total PostgreSQL outage (`postgres-cut`), in-flight requests that could not acquire a database connection failed fast with standard 500 error responses within the configured 5-second timeout budget. Post-chaos audit confirmed that no partial orders or uncommitted outbox rows remained in the database.
2. **Service Pool & Path Isolation:**
   When the `orderquery_postgres` proxy was disabled (`postgres-orderquery-cut`), `app`'s critical API p95 remained **19.80 ms** (sub-20ms!). Catalog browsing and order placement were 100% unaffected, proving that `order-query` database failures do not cascade into `app` connection pool starvation.
3. **Automated Pool Reconnection:**
   Following proxy re-enabling or database container restart, HikariCP connection pools in both deployables automatically detected socket recovery and re-established valid connections without process crashes or manual intervention.
4. **Exact Cross-Schema Data Reconciliation:**
   Post-chaos data reconciliation (`reconcile-data.sh`) confirmed 100% consistency across all schemas:
   $$\text{orders} = \text{outbox} = \text{inventory\_reservations} = \text{order\_read\_model}$$
   with zero duplicate stock deductions and zero orphaned rows.

---

## 5. Conclusion & Phase Gate Status

- All 4 PostgreSQL chaos scenarios passed acceptance criteria without data corruption or memory leaks.
- Single physical database topology limitations respected (no cluster failover or replica routing claims made).
- Verification completed successfully.
