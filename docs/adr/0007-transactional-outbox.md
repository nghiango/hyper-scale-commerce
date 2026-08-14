# ADR-0007: Transactional Outbox for Reliable Event Publishing

- Status: Accepted
- Date: 2026-08-13
- Phase: 3 — Event-Driven Architecture

## Context

ADR-0006 introduces Kafka as the event broker. Publishing an event directly to
Kafka inside the same database transaction as the business change is unsafe:
if the transaction commits but the publish fails, the event is lost; if the
publish succeeds but the transaction rolls back, a phantom event is emitted for
a change that never happened. The constitution (§5) requires durable messages
and zero intentional data loss.

## Alternatives Considered

1. **Transactional outbox (chosen)** — the business change and an outbox event
   are written atomically in the same database transaction; a relay publishes
   outbox rows to Kafka and marks them published. Durable, no loss, and a
   well-established pattern.
2. **Direct publish inside the transaction** — risks lost or phantom events;
   no atomicity between the database and the broker.
3. **Kafka transactions** — couples database and broker transactions and
   requires a transactional coordinator; unnecessary complexity for a
   single-node local deployment.
4. **Change Data Capture (e.g., Debezium)** — powerful but adds a new
   infrastructure component; overkill for the first event flow.

## Decision

Adopt the **transactional outbox** pattern:

- Each publishing context owns an `outbox_events` table in its schema (Phase 3:
  `order.outbox_events`).
- The business write and the outbox insert happen in the same database
  transaction.
- A scheduled `OutboxRelay` polls unpublished events, publishes them to Kafka,
  and marks them published.
- Delivery is at-least-once; consumers dedupe by event id.

Rationale: the outbox provides exactly-once *publishing intent* with
at-least-once *delivery*, which combined with idempotent consumers satisfies
the constitution's durability and data-loss rules without new infrastructure.

## Operational Cost

- An `outbox_events` table per publishing context.
- `OutboxRepository` (insert, claim, mark published) and `OutboxRelay`
  (scheduled poller).
- `outbox_relay_lag` metric to observe publish latency.
- Consumer-side dedupe by event id.

## Failure Modes

- **Relay down:** events remain in the outbox; nothing is lost; lag grows and
  is observable.
- **Duplicate publish:** at-least-once delivery; idempotent consumers prevent
  double effects.
- **Outbox growth:** published rows are marked and can be pruned; lag metric
  alerts on backlog.

## Consequences

- P3-03 implements the outbox infrastructure.
- P3-04 (Order) writes the order and the outbox event atomically.
- P3-05 (Inventory) consumes events idempotently.
- The outbox is a reliable publish mechanism, not event sourcing; event
  sourcing remains forbidden until a later phase.
