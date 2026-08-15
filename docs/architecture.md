# Architecture

## Current Stage

Phase 6 — Resilience Engineering (complete; Phase 7 Observability planned)

## Target Architecture

Two independently deployable services communicating exclusively through Kafka
events, sharing one PostgreSQL instance with per-service schemas.

```text
                Client
                  |
        +---------+---------+
        |                   |
        v                   v
   app (monolith)      order-query
   POST /orders        GET /orders/{id}
   GET /catalog/*      GET /orders?page=&size=
   Inventory consumer
        |                   ^
        |                   |
        v                   |
   order.outbox_events      |
        |                   |
        +--> Kafka ---------+
             order-placed
```

## Deployables

| Deployable | Module | Port | Owned schemas | Responsibilities |
|---|---|---|---|---|
| `app` | `app` | 8080 | `catalog`, `order`, `inventory` | Catalog reads, Order command (`POST /orders`), outbox relay, Inventory consumer |
| `order-query` | `order-query` | 8081 | `order_query` | OrderPlaced projection, read model, `GET /orders*` |
| contracts | `contracts` | — | — | Shared event contracts (`OrderPlacedEvent`) |

## Communication

- Cross-service communication is exclusively Kafka events (`order-placed`).
- No synchronous inter-service calls (REST/gRPC) across deployables.
- The transactional outbox in `app` guarantees durable event publication.
- `order-query` consumes with a dedicated consumer group (`order-query`) and
  projects into `order_query.order_read_model`.

## Data Ownership

Each deployable owns its persistence:

- `app` owns the `catalog`, `order`, and `inventory` schemas.
- `order-query` owns the `order_query` schema (read model only).
- Per-service Flyway migrations and jOOQ codegen with separate history tables.

## Module Boundaries

```text
app ──────────> contracts
order-query ──> contracts
```

`app` and `order-query` must not depend on each other. ArchUnit enforces
package-level dependency rules within each module.

## Monolith Internal Structure

```text
com.hyperscale.commerce
  modules
    catalog
    order
    inventory
    shared
```

Each bounded context owns its business rules and persistence; dependency
direction follows `api -> application -> domain` with infrastructure
implementing domain interfaces.

## References

- ADR-0010 — Extract the Order query side as the first service
- ADR-0011 — Monorepo module and per-service data-ownership model