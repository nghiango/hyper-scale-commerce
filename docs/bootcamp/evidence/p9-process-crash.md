# Phase 9 Evidence — Application Process Crash & Harness-Controlled Restoration Chaos

Date: 2026-08-15  
Status: **VERIFIED & PASSED**  
Harness: Toxiproxy (`ghcr.io/shopify/toxiproxy:2.11.0`), k6 (`grafana/k6:0.57.0`), Docker Compose Chaos Overlay (`performance/compose.chaos.yml`)

---

## 1. Objective & Scope

Empirically verify bounded failure, fault isolation, independent service survival, and post-restart recovery when individual deployable containers (`app` or `order-query`) are abruptly terminated via `SIGKILL` during active concurrent workload and restored explicitly by the harness.

---

## 2. Workload & Crash Topology Under Test

- **Workload:** 50 Concurrent Virtual Users running 80/10/10 mixed transactions for 40 seconds per scenario.
- **Deployables Under Test:**
  - `hyperscale-app` (Catalog & Order commands on port 8080)
  - `hyperscale-order-query` (Order queries & read projection on port 8081)
- **Controlled Process Faults:**
  1. **`app-crash`:** Abrupt `docker kill hyperscale-app` at $T=15\text{s}$; 10-second fault window; harness explicitly restarts `app` container at $T=25\text{s}$.
  2. **`order-query-crash`:** Abrupt `docker kill hyperscale-order-query` at $T=15\text{s}$; 10-second fault window; harness explicitly restarts `order-query` container at $T=25\text{s}$.

---

## 3. Experimental Results Matrix

| Scenario | Target Killed | Fault Window | HTTP Error Rate | Surviving Service Behavior | Post-Restart Health | Catch-up & Reconciliation |
|---|---|---|---|---|---|---|
| **`app-crash`** | `hyperscale-app` | 10s (T=15s $\to$ 25s) | **31.81%** (bounded) | `order-query` served `GET /orders` throughout ($\text{med} = 15.49\text{ ms}$, 100% success) | `UP` in 3s | **100% (Exact match)** |
| **`order-query-crash`** | `hyperscale-order-query` | 10s (T=15s $\to$ 25s) | **8.10%** (bounded) | `app` catalog & order writes retained SLOs (`POST /orders` $\text{p95} = 17.37\text{ ms}$) | `UP` in 4s | **50/50 (100% match)** |

---

## 4. Key Architectural Observations

1. **Deployable Boundary & Failure Isolation:**
   - Terminating `app` did not impact `order-query`'s ability to serve read queries from `order_query.order_read_model`.
   - Terminating `order-query` did not degrade `app`'s order creation (`order_post_orders_duration` p95 remained sub-18ms) because order creation commits synchronously only to `order` and `outbox_events`.
2. **Explicit Harness Restoration:**
   As required by the Phase 9 specification, container restoration was initiated explicitly by the harness (`docker start`) rather than relying on false self-healing claims.
3. **Automated Catch-up & Consumer Reconnection:**
   Upon restart, Spring Boot initialized Kafka consumers, re-established partition leases with Kafka, caught up on unprojected events from the log, and brought `order_read_model` into complete alignment.
4. **Exact Cross-Schema Data Reconciliation:**
   Post-chaos data reconciliation (`reconcile-data.sh`) confirmed 100% consistency across all schemas:
   $$\text{orders (50)} = \text{outbox (50)} = \text{inventory\_reservations (50)} = \text{order\_read\_model (50)}$$
   with zero duplicate stock deductions and zero orphaned rows.

---

## 5. Conclusion & Phase Gate Status

- Process crash and explicit restoration experiments passed all acceptance criteria.
- Single deployable isolation and post-restart backlog catch-up verified under active load.
- Verification completed successfully.
