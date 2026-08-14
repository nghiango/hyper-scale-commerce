# ADR-0010: Extract the Order Query Side as the First Service

- Status: Accepted
- Date: 2026-08-14
- Phase: 5 — Service Extraction

## Context

`docs/constitution.md` defines the engineering evolution and stage 6 is
Service Extraction. Phase 4 (ADR-0008) introduced CQRS for the Order context:
a denormalized `order.order_read_model` projected from `order-placed` events
by an `order-query` consumer group, with `GET /orders/{id}` and
`GET /orders?page=&size=` served exclusively from the read model.

The platform is still a single deployable. Independent deployment, per-service
data ownership, and the operational concerns of a distributed system (startup
order, per-service health, cross-service event flow) are unproven. The
constitution requires these capabilities before Resilience Engineering
(Phase 7) and Observability (Phase 8) can build on them.

The Order query side is the lowest-risk first extraction:

- It is read-only; it never touches the write tables.
- It owns a denormalized, rebuildable read model (ADR-0008 failure modes).
- It already consumes through a dedicated consumer group (`order-query`),
  so extraction requires no event-flow redesign.
- The write path (`POST /orders` + outbox + relay) is untouched.

## Alternatives Considered

1. **Extract the Order query side (chosen)** — move `OrderPlacedProjection`,
   `OrderQueryService`, and the `GET /orders*` endpoints into an independent
   `order-query` service communicating with the monolith exclusively via
   `order-placed` events. Builds directly on the proven CQRS projection.
2. **Extract Catalog first** — Catalog is read-only and simple, but it is not
   event-driven: extraction would require inventing a synchronous
   inter-service API (forbidden this phase) or a new event flow, proving
   less about the platform's event backbone.
3. **Extract Inventory first** — Inventory is a pure Kafka consumer with its
   own tables, but it serves no API; extracting it would not exercise an
   independently deployed HTTP service, its health endpoints, or query SLOs.
4. **Keep the monolith** — the status quo; independent deployment and
   per-service data ownership remain unproven and later phases (resilience,
   observability of distributed systems) are blocked.

## Decision

Extract the Order query side as the first independently deployable service:

- A new `order-query` deployable owns `OrderPlacedProjection` (consumer group
  `order-query`), `OrderQueryService`, and the `GET /orders/{id}` and
  `GET /orders?page=&size=` endpoints.
- The monolith (`app`) retains Catalog, the Order command side
  (`POST /orders`, outbox, relay), and the Inventory consumer; it no longer
  serves `GET /orders*`.
- Cross-service communication is exclusively Kafka events (`order-placed`).
  There are no synchronous inter-service calls (REST/gRPC) across deployables.
- The read model is owned solely by the `order-query` service (see ADR-0011
  for the schema/data-ownership decision).
- Both deployables expose their own actuator endpoints on distinct ports
  (`app`: 8080, `order-query`: 8081) and must start and become healthy
  independently, in any order, catching up via the durable topic.

Rationale: the query side is already decoupled from the write model by CQRS,
so extraction is a move — not a redesign — of proven components, and it proves
independent deployment, per-service data ownership, and cross-service event
flow with minimal risk to the write path.

## Operational Cost

- Two deployables to build, configure, and operate instead of one.
- Per-service Flyway migrations, jOOQ codegen, Kafka consumer configuration,
  and actuator endpoints (detailed in ADR-0011).
- The `order-query` consumer lag and `order_read_model_lag_seconds` gauge now
  describe a separately deployed service; both deployables' metrics must be
  scraped independently.

## Failure Modes

- **order-query down:** the monolith's write path is unaffected; queries fail
  while the service is down and recover on restart; the projection catches up
  from the durable topic.
- **Kafka unavailable at order-query startup:** the service starts and becomes
  healthy; the consumer retries with backoff (Phase 3 pattern) and catches up
  when the broker returns; queries serve an empty/stale read model meanwhile.
- **Read-your-writes gap:** unchanged from ADR-0008 — `POST /orders` returns
  the full order DTO; propagation to `GET /orders*` remains around the relay
  interval and is observable via `order_read_model_lag_seconds`.
- **Read model drift:** the read model remains rebuildable by resetting the
  `order-query` consumer group and replaying the topic.

## Consequences

- P5-02 splits the build into `app`, `order-query`, and `contracts` modules.
- P5-03 through P5-05 perform the move; P5-06 adds container images and
  Compose wiring; P5-07 proves the cross-service flow end-to-end.
- Phase 6 (Resilience Engineering) can assume independently deployable
  services communicating through events.
- The `OrderPlaced` event becomes a published contract shared by two
  deployables; changes to it must remain additive (see ADR-0011).
