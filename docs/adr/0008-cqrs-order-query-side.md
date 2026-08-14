# ADR-0008: CQRS for the Order Query Side

- Status: Accepted
- Date: 2026-08-13
- Phase: 4 — CQRS

## Context

`docs/constitution.md` defines the engineering evolution and stage 5 is CQRS.
Phase 3 introduced the event backbone (Kafka + transactional outbox) and proved
the `OrderPlaced` flow end-to-end: `POST /orders` writes `order.orders` and
`order.outbox_events` atomically, the relay publishes to `order-placed`, and
the Inventory context consumes it.

The Order context still reads its own write tables (`order.orders`,
`order.order_items`) for `GET /orders/{id}`. The read path is coupled to the
command-side model and its joins, and there is no query capability for the
requirements' "view orders" beyond a single id lookup. CQRS is the next
evolutionary step: a denormalized, query-optimized read model projected from
events.

## Alternatives Considered

1. **Event-driven read model (chosen)** — a separate `order.order_read_model`
   projected from `OrderPlaced` events by an `order-query` consumer group; the
   query API serves from it. Uses the Phase 3 event backbone and proves the
   projection pattern later phases (service extraction) will reuse.
2. **Inline projection (same transaction)** — the read model is updated in the
   write transaction. Simpler and always consistent, but couples write latency
   to read-model maintenance and does not exercise the event backbone.
3. **Query the write tables (no CQRS)** — the status quo; the read path stays
   coupled to the command model and offers no list capability.
4. **Event sourcing** — forbidden until a later phase; the read model is a
   projection, not an event-sourced aggregate.
5. **Separate query service** — service extraction, explicitly deferred to a
   later phase.

## Decision

Adopt CQRS for the Order context:

- The command side (`POST /orders`) is unchanged: it writes `order.orders` +
  `order.outbox_events` atomically.
- A denormalized read model `order.order_read_model` (order_id PK, status,
  items JSONB, created_at, updated_at) is owned by the Order context.
- `OrderPlacedProjection`, a Kafka consumer (group `order-query`), upserts the
  read model from `order-placed`; idempotent by `order_id`.
- `GET /orders/{id}` and the new `GET /orders?page=&size=` serve from the read
  model; the query path never reads the write tables.
- Eventual consistency is explicit: `POST /orders` returns the full order DTO
  (read-your-writes mitigation), and lag is observable via
  `order_read_model_lag_seconds`.
- The `OrderPlaced` payload is extended additively with `status` and
  `createdAt` (the `version` field is retained) so projections can build a
  complete snapshot.

Rationale: the event-driven read model decouples reads from the command model,
uses the proven Phase 3 backbone, and establishes the projection pattern that
service extraction will build on, while the POST response preserves the
create-then-read experience.

## Operational Cost

- A read model table and its projection consumer.
- `order_read_model_lag_seconds` gauge and `events_consumed_total` with a
  `consumer=order-query` tag.
- Read model rebuild procedure by replaying the durable topic.

## Failure Modes

- **Projection down:** reads serve stale data; lag grows and is observable;
  the projection catches up when the broker returns.
- **Read-your-writes gap:** a just-created order may not be visible in the read
  model for up to the relay interval; the POST response returns the full order
  DTO so clients are not blocked.
- **Read model drift:** the read model is rebuildable by replaying the topic.

## Consequences

- P4-06 implements the read model and the projection.
- P4-07 moves the query API to the read model.
- The projection pattern is reused by later phases (service extraction).
- The write model remains the source of truth.
