# ADR-0017: Distributed Saga and Compensating Transaction Strategy

## Status

Accepted

## Context

In HyperScale Commerce, business flows cross bounded contexts (Order Creation $\to$ Inventory Allocation $\to$ Payment Processing $\to$ Order Query Projection).

Two-Phase Commit (2PC) or distributed XA transactions across PostgreSQL and Kafka are explicitly rejected because they introduce synchronous distributed blocking locks, create single points of failure, and severely degrade availability under load (violating the CAP theorem).

We must define a formal distributed transaction and compensation pattern to handle both transient technical failures (e.g. broker unreachable) and persistent domain failures (e.g. inventory out of stock).

---

## Decision

We adopt a **Choreographed Distributed Saga** with asynchronous event-driven compensation:

1. **Transactional Step Execution:**
   - Each bounded context executes its local step within a local ACID database transaction and persists domain events to its Transactional Outbox table.
2. **Forward Recovery (Retryable Failures):**
   - If a step encounters transient infrastructure or network errors, it retries with exponential backoff and randomized jitter.
3. **Backward Recovery (Semantic Compensations):**
   - If a step encounters a non-retryable domain failure (e.g. inventory reservation fails due to insufficient stock):
     - The failing context emits a dedicated failure event (`InventoryReservationFailed`).
     - Upstream contexts consume the failure event and execute semantic compensating transactions (`Order` status transitions from `PLACED` to `CANCELLED`).
4. **Idempotency Guarantee:**
   - All saga compensation handlers must be strictly idempotent. Replaying an event multiple times must never produce duplicate compensations.

---

## Consequences

### Positive
- High availability and throughput: no blocking distributed locks.
- Loose coupling between bounded contexts.
- Guaranteed eventual consistency across independent schemas.

### Negative / Tradeoffs
- Business logic must accommodate intermediate states (e.g. order in `PLACED` state awaiting inventory confirmation).
- Requires robust monitoring for stuck sagas and Dead Letter Queues.
