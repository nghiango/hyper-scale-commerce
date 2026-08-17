# Architecture — HyperScale Commerce

## Current Stage

Phase 10 — Production Readiness, Operational Hardening & Final Certification (**CERTIFIED**)

## Target Architecture

Two independently deployable services communicating exclusively through Kafka
events, sharing one PostgreSQL instance with per-service schemas, monitored via
Micrometer Tracing & Prometheus alerts, resilient against distributed failure modes,
and certified for 10,000+ concurrent users with sub-200ms p95 latency.

```text
                  external load & test plane
             +-----------------------------------+
             | k6 scenarios + result summaries  |
             | resource/metric snapshot scripts |
             | Toxiproxy fault-injection harness|
             +----------------+------------------+
                              |
                  +-----------+-----------+
                  |                       |
                  v                       v
             app :8080              order-query :8081
       Catalog + Order command        Order queries
                  |                       ^
                  v                       |
          order.outbox_events             |
                  |                       |
                  +----> Kafka -----------+
                           |
                     Inventory consumer
                  |
                  v
             PostgreSQL 16
```

## Deployables

| Deployable | Module | Port | Owned schemas | Responsibilities |
|---|---|---|---|---|
| `app` | `app` | 8080 | `catalog`, `order`, `inventory` | Catalog reads, Order command (`POST /orders`), transactional outbox relay, Inventory consumer |
| `order-query` | `order-query` | 8081 | `order_query` | OrderPlaced projection, read model, `GET /orders*` |
| contracts | `contracts` | — | — | Shared event contracts (`OrderPlacedEvent`) |
| load-generator (test only) | `performance` | — | — | External k6 load harness driving HTTP ports 8080/8081 |
| chaos harness (test only) | `performance/chaos` | 8474 | — | Toxiproxy network latency, packet slicing, and connection cut injection |

## Communication

- Cross-service communication is exclusively Kafka events (`order-placed`).
- No synchronous inter-service calls (REST/gRPC) across deployables.
- The transactional outbox in `app` guarantees durable event publication.
- `order-query` consumes with a dedicated consumer group (`order-query`) and
  projects into `order_query.order_read_model`.
- Distributed tracing (Micrometer Tracing + Brave) and correlation IDs flow across
  HTTP requests and Kafka record headers without requiring external collector infrastructure.

## Data Ownership

Each deployable owns its persistence:

- `app` owns the `catalog`, `order`, and `inventory` schemas.
- `order-query` owns the `order_query` schema (read model only).
- Per-service Flyway migrations and jOOQ codegen with separate history tables.
- Cross-schema queries in application code are strictly forbidden. Test-only
  reconciliation scripts query owned schemas independently for consistency verification.

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

## External Load & Chaos Planes

- **Isolation:** k6 runs as an external container (`grafana/k6:0.57.0@sha256:...`) under a test-only Compose profile.
- **Toxiproxy Fault Injection:** Toxiproxy container (`ghcr.io/shopify/toxiproxy:2.11.0`) intercepts all database and Kafka traffic for deterministic chaos simulation.
- **Black-Box Access:** Drives public HTTP ports on `app` (8080) and `order-query` (8081).
- **Zero Runtime Contamination:** No test libraries, test controllers, or load agents exist inside `app` or `order-query`.

## References

- ADR-0010 — Extract the Order query side as the first service
- ADR-0011 — Monorepo module and per-service data-ownership model
- ADR-0012 — Resilience Strategy for Distributed Communication
- ADR-0013 — Observability Strategy for the Two-Service Platform
- ADR-0014 — Load-Test Strategy and Qualification Model
- ADR-0015 — Chaos Engineering, Network Fault Injection, and Distributed Failure Strategy
- ADR-0016 — Production Hardening, Security, Lifecycle Management, and Operational Alerting Strategy