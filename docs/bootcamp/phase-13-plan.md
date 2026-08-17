# Phase 13 Plan: Distributed Stream Operations, DLQ Replay & Out-of-Order Event Resilience

**Phase:** Phase 13 — Distributed Stream Operations, DLQ Replay & Out-of-Order Event Resilience

**Status:** **COMPLETED**

**Date:** 2026-08-17

## 1. Phase Objective

Implement operational event stream capabilities in HyperScale Commerce:
1. Dead Letter Queue (DLQ) administrative inspection, batch filtering, and safe re-drive/replay mechanism.
2. Monotonic aggregate versioning and out-of-order event tolerance in CQRS read-model projections.
3. Per-instance client fixed-window rate limiting with standard HTTP 429 response handling.
4. Comprehensive qualification suite and operational runbooks for distributed stream management.

---

## 2. Why This Phase Exists

While Phases 1 through 12 established high-throughput event publishing, CQRS projections, Choreographed Sagas, and multi-tier caching, real-world distributed architectures inevitably encounter operational stream edge cases:
- When downstream consumers fail after bounded retries, poison or unprocessable events land in dead letter queues (`*-dlq`). Operators need automated tools to inspect, redact, and safely replay these messages once root causes are resolved.
- During Kafka partition rebalancing or network retries, events may arrive out of chronological order. Projections must be resilient against out-of-sequence updates to prevent stale state regressions.
- Upstream client surges require client-level rate limiting to prevent single-tenant starvation.

---

## 3. Starting Architecture / State

- **Monolith (`app`):** Handles catalog, orders, stock reservations, saga compensation, outbox relay with `SKIP LOCKED`, caching with Caffeine, and storage pruning.
- **Query Service (`order-query`):** Consumes `order-placed` and `order-cancelled` Kafka topics, updates PostgreSQL `ORDER_READ_MODEL`, handles DLQ routing on failures.
- **Event Topics:** `order-placed`, `order-cancelled`, `order-placed-dlq`, `order-cancelled-dlq`.

---

## 4. Target Architecture / State

- **DLQ Replay Engine:** An operational service (`DlqReplayService`) capable of consuming dead-lettered messages, parsing metadata, and re-injecting them into primary event streams with re-drive headers (`X-Redrive-Count`).
- **Monotonic Versioned Projections:** Aggregate events carry `aggregateVersion: Long`. Projections enforce `ORDER_READ_MODEL.VERSION < incoming.VERSION`, preventing out-of-order state overwrites.
- **Per-Instance Fixed-Window Rate Limiter:** Application servlet filter enforcing configurable requests-per-minute per client IP / API key within one `app` process, returning HTTP 429 and `Retry-After` headers.
- **Observability & Operational Verification:** DLQ depth, re-drive counters, rate-limiting metrics, and automated replay tests.

---

## 5. Problems This Phase Addresses

1. **Stranded Dead Letter Events:** Preventing manual, risky SQL patching when transient consumer errors resolve.
2. **Out-of-Order State Regressions:** Eliminating race conditions where delayed events overwrite newer state.
3. **Noisy Neighbor & DoS Mitigation:** Preventing rogue clients from overwhelming public catalog and checkout endpoints.

---

## 6. Architecture Changes

- Update `OrderPlaced` and `OrderCancelled` contracts with `aggregateVersion: Long`.
- Add `version` column to `order_query.order_read_model` with Flyway migration.
- Add `DlqReplayService` and administrative endpoints for DLQ re-drive.
- Add `ClientRateLimiterFilter` with a bounded fixed-window counter.

---

## 7. Technology Changes

- No new external infrastructure introduced.
- Uses existing Spring Kafka, Caffeine, PostgreSQL 16, and Micrometer.

---

## 8. Non-Functional Requirements

- Replay operations must be idempotent and must not create duplicate orders or inventory leaks.
- Out-of-order events must be safely discarded or reconciled with zero data corruption.
- Rate limiting filter overhead must be $< 0.1\text{ms}$ per request.

---

## 9. Performance Expectations

- Rate-limited traffic must return HTTP 429 within $< 2\text{ms}$.
- DLQ replay throughput $> 500\text{ events/sec}$.

---

## 10. Reliability Expectations

- Replayed messages that fail repeatedly must be routed to a terminal DLQ after maximum redrives ($3$).
- Zero deadlocks during concurrent projection updates.

---

## 11. Observability Requirements

- Prometheus metrics:
  - `dlq_replayed_events_total{topic="...", outcome="success|failure"}`
  - `events_out_of_order_total{event_type="..."}`
  - `http_rate_limited_requests_total{endpoint="..."}`

---

## 12. Security Considerations

- DLQ inspection and replay operations restricted to administrative interfaces / secure roles.

---

## 13. Data Considerations

- Additive non-breaking Flyway schema migrations on `order_query.order_read_model`.
- Event schema evolution backward compatibility preserved.

---

## 14. Explicitly Out-of-Scope Capabilities

- External distributed Redis cluster (in-memory token bucket suffices).
- A cluster-global quota shared across multiple `app` replicas; Phase 13 proves
  only per-instance enforcement.
- Complex UI dashboards (REST APIs and k6 tests suffice).

---

## 15. Dependencies on the Previous Phase

- Phase 12 (Multi-tier caching, outbox `SKIP LOCKED`, event-driven invalidation) fully completed.

---

## 16. Risks & Mitigations

- **Risk:** Infinite DLQ replay loop if underlying bug is unfixed.
  - **Mitigation:** Strict `X-Redrive-Count` header check with maximum limit.
- **Risk:** High memory usage in sliding window rate limiter.
  - **Mitigation:** Caffeine cache with size bounding and 1-minute TTL eviction.

---

## 17. Architecture Decision Records

- **ADR-0022:** Distributed Stream Operations, DLQ Replay & Out-of-Order Event Resilience (Accepted).

---

## 18. Ordered Implementation Tasks

### P13-01 — Monotonic Aggregate Versioning & Out-of-Order Event Guard
- **Objective:** Add aggregate versioning to events and enforce monotonic version ordering in `OrderPlacedProjection` and `OrderCancelledProjection`.
- **Dependencies:** None (builds on Phase 12).
- **Scope:** `contracts/`, `order-query/` Flyway migration `V2__add_version_to_order_read_model.sql`, projection consumers.
- **Acceptance Criteria:** Stale/older event versions are safely ignored; newer versions update read model; tests pass.

### P13-02 — Dead Letter Queue Inspection & Administrative Replay Engine
- **Objective:** Implement `DlqReplayService` and management controller to inspect and safely re-inject DLQ events back to active topics.
- **Dependencies:** P13-01.
- **Scope:** `order-query/src/main/kotlin/com/hyperscale/commerce/orderquery/admin/DlqReplayService.kt`, `DlqAdminController.kt`.
- **Acceptance Criteria:** Dead-lettered events re-drive to primary topic with `X-Redrive-Count`; re-processed idempotently.

### P13-03 — Per-Instance Client Rate Limiter Filter
- **Objective:** Implement an in-memory fixed-window client rate limiter with HTTP 429 and `Retry-After`.
- **Dependencies:** None.
- **Scope:** `app/src/main/kotlin/com/hyperscale/commerce/config/ratelimit/ClientRateLimitFilter.kt`, properties, metrics.
- **Acceptance Criteria:** Clients exceeding configured request threshold receive 429; conformant clients proceed with zero latency impact.

### P13-04 — Stream Operations & Out-of-Order Resilience Qualification
- **Objective:** Create k6 and end-to-end resilience test suite validating out-of-order delivery, DLQ replay recovery, and rate limit shedding under load.
- **Dependencies:** P13-01 through P13-03.
- **Scope:** `performance/k6/stream-resilience.js`, `make load-stream-resilience`.
- **Acceptance Criteria:** 100% data reconciliation, successful DLQ re-drive, zero state corruption under reordered events.

### P13-05 — Phase 13 Review & Application-Level Platform Qualification
- **Objective:** Consolidate evidence in `docs/bootcamp/evidence/p13-stream-operations.md` and complete Phase 13 application-level qualification without claiming infrastructure high availability.
- **Dependencies:** P13-01 through P13-04.
- **Scope:** Evidence documentation, runbook updates, and final sign-off.
- **Acceptance Criteria:** All Phase 13 exit criteria satisfied.

---

## 19. Dependency Graph

```text
P13-01 ──+──> P13-02 ──+
         |             |
P13-03 ──+─────────────+──> P13-04 ──> P13-05
```

---

## 20. Verification Requirements for Every Task

1. Unit and ArchUnit tests validating version guards, rate limiting, and replay mechanics.
2. Integration tests with Testcontainers PostgreSQL and Kafka simulating reordered event arrival and dead-letter re-drive.
3. High-throughput performance and load qualification scenario.
4. Clean `make verify` check.

---

## 21. Phase Exit Criteria

1. Monotonic aggregate versioning prevents out-of-order event state regressions.
2. DLQ replay engine can re-inject dead-lettered events with bounded re-drive limits.
3. Client rate limiting filter enforces request quotas with HTTP 429 responses.
4. End-to-end qualification and 100% cross-schema data reconciliation verified.
5. All automated unit, integration, and architecture tests pass cleanly.
