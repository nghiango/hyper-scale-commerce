# Workload Contract & Scenario Specifications

This document defines the authoritative workload models, critical API classifications, user journeys, data sets, scenario profiles, pass/fail thresholds, and reconciliation contracts for Phase 8 Load Engineering.

---

## 1. Critical API Classification & Latency SLOs

In accordance with `docs/constitution.md`, `docs/adr/0014-load-test-strategy.md`, and `docs/observability/slos.md`, the platform defines 5 Critical APIs. Each must sustain end-to-end client latency with **p95 < 200ms** during accepted steady-state qualification runs:

| API Group | Service | Endpoint | HTTP Method | Critical Classification | Target SLO (p95) |
|---|---|---|---|---|---|
| **Catalog Detail** | `app:8080` | `/catalog/products/{id}` | `GET` | **Critical** | $< 200\text{ ms}$ |
| **Catalog Browse** | `app:8080` | `/catalog/products?page=0&size=20` | `GET` | **Critical** | $< 200\text{ ms}$ |
| **Order Command** | `app:8080` | `/orders` | `POST` | **Critical** | $< 200\text{ ms}$ |
| **Order Detail Query** | `order-query:8081` | `/orders/{id}` | `GET` | **Critical** | $< 200\text{ ms}$ |
| **Order History Query** | `order-query:8081` | `/orders?page=0&size=20` | `GET` | **Critical** | $< 200\text{ ms}$ |
| *Catalog Search* | `app:8080` | `/catalog/products?query={term}` | `GET` | *Diagnostic (Non-Critical)* | Measured baseline (unbounded) |

*(Note: Catalog substring search is measured as a reference diagnostic but excluded from the critical sub-200ms SLO due to its known sequential scan behavior; dedicated search indexing is deferred to future architecture phases).*

---

## 2. Distinction: Concurrency vs. Offered Rate vs. Achieved Throughput

To prevent conflation between concurrency and request rates:

1. **Concurrency (Virtual Users / VUs):** The count of simultaneously active simulated clients. In the closed model (10,000 VUs), each VU executes user journeys, waits for HTTP responses, and applies think time before the next action.
2. **Offered Arrival Rate ($\text{RPS}_{\text{offered}}$):** The target request rate generated independently of system response time in open arrival-rate scenarios (e.g., during the 5x spike).
3. **Achieved Throughput ($\text{RPS}_{\text{achieved}}$):** The rate of successfully completed HTTP responses received by k6 per second.

---

## 3. Representative Traffic Mix & User Journeys

The qualification workload simulates representative e-commerce traffic with an **80 / 10 / 10** request split:

```text
                               Total Workload (100%)
                                        |
       +--------------------------------+-------------------------------+
       |                                |                               |
80% Catalog Reads                10% Order Commands              10% Order Queries
  - Browse List (50%)              - POST /orders (100%)           - List Orders (50%)
  - Product Detail (50%)                                           - Get Order by ID (50%)
```

### Synthetic User Journeys

#### Journey 1: Window Shopper (Catalog Browsing — 80% Weight)
1. **Browse Catalog:** `GET /catalog/products?page={p}&size=20` with page $p \in [0, 10]$.
2. **Think Time:** Random pause between $1.0\text{s}$ and $3.0\text{s}$.
3. **View Product Detail:** `GET /catalog/products/{id}` for a selected product ID.
4. **Think Time:** Random pause between $1.0\text{s}$ and $2.0\text{s}$.

#### Journey 2: Buyer (Order Creation & Projection Verification — 10% Weight)
1. **Create Order:** `POST /orders` with payload:
   ```json
   {
     "items": [
       {"sku": "PROD-000123", "quantity": 1}
     ]
   }
   ```
2. **Capture Order ID:** Extract `id` from `201 Created` response.
3. **Asynchronous Visibility Check:** Poll `GET /orders/{id}` on `order-query:8081` until `200 OK` or deadline ($5.0\text{s}$). Record projection latency.
4. **Think Time:** Random pause between $2.0\text{s}$ and $4.0\text{s}$.

#### Journey 3: Returning Customer (Order History Query — 10% Weight)
1. **List Recent Orders:** `GET /orders?page=0&size=20` on `order-query:8081`.
2. **Think Time:** Random pause between $1.5\text{s}$ and $3.0\text{s}$.
3. **View Order Detail:** `GET /orders/{id}` for an existing order.
4. **Think Time:** Random pause between $1.0\text{s}$ and $2.0\text{s}$.

---

## 4. Deterministic Test Data & Cardinality

To guarantee reproducibility across repeated runs:

- **Catalog Cardinality:** 10,000 deterministic products pre-seeded into `catalog.products` (`PROD-000001` through `PROD-010000`).
- **Inventory Cardinality:** Pre-allocated inventory of `1,000,000` units per SKU in `inventory.inventory_items` to prevent artificial out-of-stock rejections during load runs.
- **Random Seed:** k6 PRNG seed is fixed to `42` for reproducible SKU selection and think-time distributions.
- **Order ID Tracking:** All generated Order IDs from `POST /orders` responses are recorded in memory/ephemeral log files for post-test schema reconciliation.

---

## 5. Scenario Profiles

```text
   Smoke Profile:
   [10 VUs] ── (30s) ──> [Done]

   Baseline Stepped Profile:
   [100 VUs] ──> [500 VUs] ──> [1,000 VUs] ──> [2,500 VUs] ──> [5,000 VUs] ──> [10,000 VUs]
   (2 min/step)

   10k VU Qualification Profile (Closed Model):
   Ramp (3m) ──> Steady State 10,000 VUs (15m) ──> Ramp Down (2m) ──> Drain (1m)

   5x Spike Qualification Profile (Open Arrival-Rate Model):
   Warm-up (2m @ 1x) ──> Burst (15s ramp -> 60s @ 5x) ──> Recovery (5m @ 1x) ──> Drain (1m)
```

### Profile 1: `smoke` (Local Sanity & Integration Validation)
- **Executor:** `constant-vus`
- **Concurrency:** 10 VUs
- **Duration:** 30 seconds
- **Data:** Pre-seeded Catalog, clean Order tables
- **Thresholds:**
  - `http_req_duration{endpoint:critical}`: p95 $< 200\text{ ms}$
  - `http_req_failed`: $< 0.1\%$
  - Projection lag: p95 $\le 2.0\text{ s}$
- **Stop Condition:** Immediate exit on HTTP 5xx or database connection error.

### Profile 2: `baseline` (Stepped Saturation & Knee Discovery)
- **Executor:** `ramping-vus`
- **Steps:**
  - Step 1: 100 VUs for 2 minutes
  - Step 2: 500 VUs for 2 minutes
  - Step 3: 1,000 VUs for 2 minutes
  - Step 4: 2,500 VUs for 2 minutes
  - Step 5: 5,000 VUs for 2 minutes
  - Step 6: 10,000 VUs for 2 minutes
- **Thresholds:** Diagnostic measurement; identifies first latency SLO breach or pool saturation point.
- **Stop Condition:** Generator CPU $> 90\%$ or consecutive error rate $> 5\%$.

### Profile 3: `qualification-10k` (10,000 Concurrent VU Target Verification)
- **Executor:** `ramping-vus` (Closed Concurrency Model)
- **Stages:**
  - Stage 1: Ramp from 0 to 10,000 VUs over 3 minutes.
  - Stage 2: **Steady-state hold at 10,000 active VUs for 15 minutes.**
  - Stage 3: Ramp down to 0 VUs over 2 minutes.
  - Stage 4: Idle drain period of 1 minute.
- **Pass/Fail Thresholds (Evaluated strictly on Steady-State Stage):**
  - Active VUs: $\ge 10,000$ throughout steady state.
  - `http_req_duration{endpoint:get_product_by_id}`: p95 $< 200\text{ ms}$
  - `http_req_duration{endpoint:list_products}`: p95 $< 200\text{ ms}$
  - `http_req_duration{endpoint:post_orders}`: p95 $< 200\text{ ms}$
  - `http_req_duration{endpoint:get_order_by_id}`: p95 $< 200\text{ ms}$
  - `http_req_duration{endpoint:list_orders}`: p95 $< 200\text{ ms}$
  - `http_req_failed`: $< 0.1\%$ ($\ge 99.9\%$ success rate).
  - Projection visibility: p95 $\le 2.0\text{ s}$ during steady state.
  - Generator dropped iterations: `= 0`.
- **Stop Condition:** Readiness probe failure or service restart.

### Profile 4: `qualification-spike-5x` (5x Traffic Spike & Recovery Verification)
- **Executor:** `ramping-arrival-rate` (Open Arrival-Rate Model)
- **Parameters:**
  - Baseline rate ($1\times$): Sustainable rate established in baseline/qualification (e.g., $500\text{ RPS}$).
  - Spike rate ($5\times$): $5 \times \text{baseline rate}$ (e.g., $2,500\text{ RPS}$).
- **Stages:**
  - Stage 1: Hold $1\times$ baseline for 2 minutes (steady-state reference).
  - Stage 2: Ramp from $1\times$ to $5\times$ over 15 seconds.
  - Stage 3: **Sustain $5\times$ burst for 60 seconds.**
  - Stage 4: Ramp down from $5\times$ to $1\times$ over 15 seconds.
  - Stage 5: **Observe recovery at $1\times$ baseline for 5 minutes.**
  - Stage 6: Drain period of 1 minute.
- **Pass/Fail Thresholds:**
  - Peak burst error rate: $< 1.0\%$ (no process crashes, no unhandled exceptions).
  - Recovery period latency: Return to p95 $< 200\text{ ms}$ on critical APIs within 5 minutes.
  - Recovery period success rate: $\ge 99.9\%$ during the final minute of recovery.
  - Backlog resolution: Outbox depth and consumer lag return to baseline steady-state bands.
  - Zero lost orders: Reconcile all accepted `POST /orders` after drain.

---

## 6. HTTP Status Expectations & Polling Treatment

| Endpoint | Expected Status | Description & Handling |
|---|---|---|
| `GET /catalog/products/*` | `200 OK` | Product details / list. Any other status is counted as an HTTP failure. |
| `POST /orders` | `201 Created` | Order accepted and persisted into outbox. Any non-201 response is counted as an HTTP failure. |
| `GET /orders/{id}` (Regular Query) | `200 OK` | Order found in read model. |
| `GET /orders/{id}` (Projection Polling) | `200 OK` (eventual) | Expected initial `404 Not Found` while asynchronous projection is in flight is handled internally by the polling helper. It is **not** counted toward `http_req_failed`. If `200 OK` is not received before the 5.0s hard timeout, the iteration records a projection failure. |
| `GET /orders?page=...` | `200 OK` | Order history list. |

---

## 7. Data Reconciliation & Zero Data Loss Contract

After every load execution and subsequent 1-minute drain period, the test runner executes an automated SQL reconciliation check against the PostgreSQL database.

### Reconciliation Formula
$$\text{Accepted } \texttt{POST /orders} \equiv N_{\text{accepted}}$$

The reconciliation script asserts:
1. **Outbox Events Count:**
   $$\text{COUNT}(*) \text{ FROM } \texttt{order.outbox_events} \equiv N_{\text{accepted}}$$
2. **Published Outbox Events:**
   $$\text{COUNT}(*) \text{ FROM } \texttt{order.outbox_events WHERE published = FALSE} \equiv 0$$
3. **Inventory Reservations Count:**
   $$\text{COUNT}(*) \text{ FROM } \texttt{inventory.inventory_reservations} \equiv N_{\text{accepted}}$$
4. **Order Query Read Model Count:**
   $$\text{COUNT}(*) \text{ FROM } \texttt{order_query.orders} \equiv N_{\text{accepted}}$$
5. **No Duplicate Business Effects:**
   $$\text{COUNT}(\text{DISTINCT } \texttt{order\_id}) \text{ FROM } \texttt{order\_query.orders} \equiv \text{COUNT}(*) \text{ FROM } \texttt{order\_query.orders}$$
6. **DLQ Purity:**
   $$\text{DLQ Message Count} \equiv 0$$

If any count mismatch, unhandled outbox row, duplicate read-model record, or DLQ message is detected, the run is flagged as a **DATA INTEGRITY FAILURE** regardless of latency or throughput results.

---

## 8. Phase 14 High Availability & Failure Recovery Scenarios

In accordance with `docs/adr/0023-multi-replica-runtime-and-kafka-ha.md` and `docs/bootcamp/phase-14-plan.md`, Phase 14 introduces multi-replica service topologies and a 3-broker Kafka KRaft cluster. The following profiles govern HA and failure recovery qualification:

### Profile 5: `ha-baseline` (Multi-Replica Ingress No-Fault Steady State)
- **Topology:** $\ge 2$ `app` replicas, $\ge 2$ `order-query` replicas, 1 HAProxy ingress, 3 Kafka KRaft nodes, 1 PostgreSQL primary.
- **Executor:** `ramping-vus` (Closed Concurrency Model).
- **Workload:** Standard 80/10/10 mix through HAProxy ports 8080 and 8081.
- **Pass/Fail Thresholds:**
  - Critical API latency: p95 $< 200\text{ ms}$.
  - Ingress proxy overhead: p95 $< 5\text{ ms}$ relative to direct service control.
  - HTTP success rate: $\ge 99.9\%$.
  - Load distribution: All registered healthy backends receive $\ge 20\%$ of requests.

### Profile 6: `ha-replica-failover` (Stateless Instance Termination under Load)
- **Target:** 1 `app` or `order-query` replica terminated via `SIGKILL` during active load.
- **Hypothesis:** Surviving replica(s) absorb incoming traffic; HAProxy removes dead backend within health check deadline ($\le 5\text{s}$).
- **Pass/Fail Thresholds:**
  - Ingress health convergence: Dead backend removed within $\le 5\text{s}$.
  - Latency recovery: Critical API p95 recovers $< 200\text{ ms}$ within 30 seconds of failure.
  - Zero unhandled 5xx on surviving backends.
  - Data integrity: 100% reconciliation after drain.

### Profile 7: `ha-broker-failover` (Kafka Leader Broker Termination under Load)
- **Target:** 1 active Kafka partition leader broker terminated via `SIGKILL` during active `POST /orders` stream.
- **Hypothesis:** KRaft quorum (2 of 3) elects new partition leaders; producers with `acks=all` and `min.insync.replicas=2` continue without acknowledged data loss.
- **Pass/Fail Thresholds:**
  - Leader election & metadata convergence: $\le 5\text{s}$.
  - Outbox publication recovery: Lag drains back to steady-state within $\le 60\text{s}$ of cluster stabilization.
  - DLQ count: $= 0$ unexpected errors.
  - Data integrity: 100% reconciliation.

### Minimum Environment Specification
- **CPU:** $\ge 4$ cores dedicated to Docker Engine.
- **Memory:** $\ge 8\text{ GB}$ RAM dedicated to Docker Engine.
- **Disk:** $\ge 20\text{ GB}$ free local storage for ephemeral container volumes and test logs.
