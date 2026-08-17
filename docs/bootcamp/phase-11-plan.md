# HyperScale Commerce — Phase 11 Implementation Plan

**Phase:** Phase 11 — Advanced Distributed Workflows, Saga Compensations & Overload Protection  
**Status:** **PLANNED**  
**Author:** AI Distributed Systems Architect  
**Date:** 2026-08-16  

---

## 1. Phase Objective

The objective of Phase 11 is to implement and empirically verify enterprise-grade distributed workflow capabilities:
1. **API Idempotency Key Engine (ADR-0018):** Prevent duplicate order placements and double charging during client network timeouts and retries.
2. **Choreographed Saga Compensations (ADR-0017):** Implement automated compensating transactions across bounded contexts when downstream operations fail (e.g. inventory out of stock).
3. **Adaptive Load Shedding & Ingress Protection (ADR-0020):** Implement priority-based admission control to protect critical checkout transactions during catastrophic traffic spikes.
4. **Event Schema Evolution Verification (ADR-0019):** Implement automated compatibility verification tests for domain event contracts.

---

## 2. Why This Phase Exists

While Phase 10 certified the platform against high-concurrency performance and infrastructure chaos, enterprise distributed systems encounter edge cases that occur at the application and protocol layers:
- **Client Network Retries:** Mobile devices and frontends frequently retry `POST /orders` requests when cellular networks drop connections after the server has already committed the transaction. Without an idempotency key engine, duplicate orders are created.
- **Domain Business Failures:** When stock is exhausted during a concurrent checkout race, the inventory reservation fails. The system must execute semantic compensation (`OrderCancelled`) to restore consistency.
- **Catastrophic Traffic Overload:** During 10x spikes or DDoS events, queueing requests causes runaway latency and memory exhaustion. The system must shed low-priority browsing traffic to guarantee checkout survivability.
- **Zero-Downtime Schema Evolution:** Event contracts evolve; automated tests must continuously enforce that all domain events maintain full backward and forward compatibility.

---

## 3. Starting Architecture / State

- **Deployables:** `app` (Port 8080) and `order-query` (Port 8081).
- **Data Tier:** PostgreSQL 16 with schema isolation (`catalog`, `order`, `inventory`, `order_query`).
- **Event Bus:** Apache Kafka 3.7.0 with 3-partition `order-placed` topic and `order-placed-dlq`.
- **Fault Injection & Load Harness:** Toxiproxy 2.11.0 and k6 0.57.0.
- **Baseline Certification:** Phase 10 certified for 10,000 concurrent VUs with sub-5ms p95 latency, graceful shutdown, and 100% data reconciliation.

---

## 4. Target Architecture / State

- **Idempotency Pipeline:** `POST /orders` accepts `Idempotency-Key` header, atomically claims keys in `"order".idempotency_keys`, rejects concurrent duplicates with HTTP 409, and returns cached HTTP 201 responses on replay.
- **Choreographed Saga:** When inventory reservation fails due to insufficient stock, `inventory` consumer publishes `InventoryReservationFailedEvent`. `order` context consumes the event, cancels the order (`status = 'CANCELLED'`), persists `OrderCancelledEvent` to outbox, and `order-query` updates the read model.
- **Adaptive Load Shedder:** Ingress filter dynamically monitors p90 response time; when latency exceeds 200ms, it sheds `GET /catalog/products` traffic with HTTP 429/503 while preserving 100% of `POST /orders` checkout requests.
- **Compatibility Test Suite:** Automated contract verification tests validating additive-only schema evolution and unknown property tolerance.

---

## 5. Problems This Phase Addresses

1. **Duplicate Orders from Network Retries:** Eliminating double billing and ghost orders caused by client retries after network disconnects.
2. **Inconsistent Order States on Out-of-Stock:** Ensuring orders are automatically marked `CANCELLED` and read models updated when inventory reservation fails.
3. **Cascading Collapse Under Extreme Spikes:** Preventing JVM out-of-memory crashes by shedding non-critical read traffic during extreme load.
4. **Schema Breaking Changes:** Preventing breaking contract modifications from reaching production.

---

## 6. Architecture Changes

- Add Flyway migration `V7__create_idempotency_keys.sql` in `app` creating `"order".idempotency_keys`.
- Implement `IdempotencyFilter` in `app` intercepting `POST /orders`.
- Add `InventoryReservationFailedEvent` and `OrderCancelledEvent` contracts to `contracts` module.
- Implement Saga compensation listener in `app` (`order` context) handling `InventoryReservationFailedEvent`.
- Implement `OrderCancelled` consumer projection in `order-query`.
- Implement `AdaptiveLoadShedderFilter` in `app` with priority-tiered shedding.

---

## 7. Technology Changes

No new infrastructure technologies are required. Phase 11 leverages existing tools:
- Spring Boot 3.4.3 (Web Filters, Transaction Management).
- PostgreSQL 16 (`idempotency_keys` table with atomic row locks).
- Apache Kafka 3.7.0 (`inventory-events`, `order-events` topics).
- ArchUnit & JUnit 5 (Schema compatibility tests).
- k6 0.57.0 (Saga and idempotency qualification scenarios).

---

## 8. Non-Functional Requirements

- **Idempotency Overhead:** $< 2\text{ms}$ additional latency on `POST /orders`.
- **Saga Compensation Latency:** Order cancellation projected within $< 100\text{ms}$ of inventory failure.
- **Overload Protection:** Checkout success rate remains $100\%$ during 10x spike load shedding.
- **Zero Data Loss:** 100% reconciliation across orders, cancellations, reservations, and read model.

---

## 9. Performance Expectations

- Standard 10k VU traffic maintains p95 $< 50\text{ms}$ with idempotency key tracking active.
- Concurrent duplicate requests with the same idempotency key resolve within $< 5\text{ms}$ with HTTP 409 or cached 201.

---

## 10. Reliability Expectations

- 100% duplicate suppression for retried requests.
- Zero stuck sagas; all failed reservations result in a final `CANCELLED` order state.
- Automated recovery when load shedding triggers and traffic normalizes.

---

## 11. Observability Requirements

- Prometheus metrics exported:
  - `idempotency_requests_total{status="new|hit|in_flight_conflict"}`
  - `saga_compensations_total{reason="out_of_stock"}`
  - `load_shedding_dropped_total{endpoint="/catalog/products"}`

---

## 12. Security Considerations

- Idempotency keys scoped to authenticated client session / user ID to prevent key-hijacking.
- Request payload hash stored in `idempotency_keys` to detect key reuse with modified payloads.

---

## 13. Data Considerations

- `"order".idempotency_keys` partitioned or indexed on `expires_at` with 24-hour retention.
- Reconcile script updated to verify `CANCELLED` order statuses and zero orphaned reservations.

---

## 14. Explicitly Out-of-Scope Capabilities

- Distributed XA Two-Phase Commit transactions.
- Heavy BPMN workflow engines (e.g. Camunda, Temporal).
- Hardware security modules (HSM).

---

## 15. Dependencies on Previous Phase

- Builds on Phase 10 hardened configuration, graceful shutdown, and verified Docker Compose environment.

---

## 16. Risks & Mitigations

| Risk | Impact | Mitigation Strategy |
|---|---|---|
| Concurrent requests with identical idempotency key arriving simultaneously | Race condition creating duplicate rows | PostgreSQL `UNIQUE` constraint on `key` with atomic insert / conflict resolution |
| Deadlock between Saga compensation and read model projection | Delayed order cancellation | Idempotent updates with `WHERE status <> 'CANCELLED'` and optimistic locking |
| Over-aggressive load shedding dropping legitimate traffic | Reduced user conversion | Little's Law AIMD smoothing window (60s average) before shedding |

---

## 17. ADRs Required

- Covered by approved ADRs:
  - **ADR-0017:** Distributed Saga and Compensating Transaction Strategy
  - **ADR-0018:** API Idempotency Keys and Distributed Request Deduplication
  - **ADR-0019:** Event Schema Evolution and Versioning Strategy
  - **ADR-0020:** Adaptive Load Shedding, Rate Limiting, and Overload Protection

---

## 18. Ordered Implementation Tasks

### P11-01 — API Idempotency Key Engine & Deduplication Pipeline

- **Objective:** Implement the API Idempotency Key filter, PostgreSQL schema, request hashing, and cached response replay for `POST /orders`.
- **Dependencies:** None.
- **Scope:** Flyway migration `V7__create_idempotency_keys.sql`, `IdempotencyFilter.kt`, `IdempotencyService.kt`, unit & integration tests.
- **Acceptance Criteria:** Duplicate requests return cached 201; concurrent in-flight requests return 409; key mismatch returns 422.

### P11-02 — Choreographed Saga Compensation Flow

- **Objective:** Implement end-to-end failure compensation when inventory reservation fails (out of stock).
- **Dependencies:** P11-01.
- **Scope:** `contracts/`, `InventoryConsumer.kt`, `OrderCompensationService.kt`, `OrderProjectionConsumer.kt`.
- **Acceptance Criteria:** Out-of-stock reservation triggers `InventoryReservationFailedEvent`, order status transitions to `CANCELLED`, read model reflects `CANCELLED`.

### P11-03 — Priority-Tiered Adaptive Load Shedder

- **Objective:** Implement ingress adaptive concurrency limiter protecting `POST /orders` while shedding low-priority catalog browsing during traffic surges.
- **Dependencies:** P11-01.
- **Scope:** `AdaptiveLoadShedderFilter.kt`, configuration, metrics.
- **Acceptance Criteria:** Under simulated latency breach ($>200\text{ms}$), catalog requests return 429/503 while checkout `POST /orders` succeeds with 100% availability.

### P11-04 — Event Schema Evolution & Compatibility Test Suite

- **Objective:** Implement automated contract and ArchUnit verification tests enforcing backward and forward compatibility rules.
- **Dependencies:** P11-02.
- **Scope:** `contracts/src/test/kotlin/` and `app/src/test/kotlin/`.
- **Acceptance Criteria:** Tests verify deserialization of payloads with unknown fields and additive-only schema evolution.

### P11-05 — Distributed Workflows Chaos & Verification Suite

- **Objective:** Create k6 and chaos test scenarios qualifying concurrent duplicate retries, out-of-stock sagas, and load shedding.
- **Dependencies:** P11-01 through P11-04.
- **Scope:** `performance/k6/saga-idempotency.js`, `performance/scripts/reconcile-data.sh`.
- **Acceptance Criteria:** 100% duplicate suppression under load; 100% data reconciliation for placed and cancelled orders.

### P11-06 — Phase 11 Consolidation & Review

- **Objective:** Consolidate evidence dossier in `docs/bootcamp/evidence/p11-distributed-workflows.md` and execute formal phase review.
- **Dependencies:** P11-01 through P11-05.
- **Scope:** Evidence documentation and phase sign-off.
- **Acceptance Criteria:** All Phase 11 exit criteria satisfied; `make verify` and load tests passing.

---

## 19. Dependency Graph

```text
P11-01 ──+──> P11-02 ──+──> P11-04 ──+
         |             |             |
         +──> P11-03 ──+             +──> P11-05 ──> P11-06
```

---

## 20. Verification Requirements for Every Task

- Automated build and unit tests pass (`make verify`).
- Regression smoke tests pass (`make load-smoke`, `make chaos-smoke`).
- 100% cross-schema data reconciliation verified (`performance/scripts/reconcile-data.sh`).

---

## 21. Phase Exit Criteria

1. API Idempotency Key engine active and verified with 0 duplicate orders.
2. Choreographed Saga compensation verified with 100% status accuracy.
3. Priority load shedding verified under traffic saturation.
4. Event schema compatibility test suite committed and passing.
5. High-concurrency qualification test passes with p95 $< 200\text{ms}$ and 100% data reconciliation.
6. Phase 11 review report approved.
