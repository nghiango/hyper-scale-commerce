# Architecture — HyperScale Commerce

## Current Stage

Phase 13 — Distributed Stream Operations, DLQ Replay & Out-of-Order Event
Resilience (**COMPLETED**).

Phase 14 — Multi-Replica Runtime & Kafka High Availability is planned but is
not yet approved for implementation.

## Current Verified Architecture

Two independently deployable services communicating exclusively through Kafka
events, sharing one PostgreSQL instance with per-service schemas. The platform
has application-level resilience mechanisms and bounded local load and chaos
evidence. It is not yet an infrastructure-high-availability topology.

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
       Inventory + compensation       CQRS projections
       Outbox + per-instance limit    DLQ replay API
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
| `app` | `app` | 8080 | `catalog`, `order`, `inventory` | Catalog reads, Order commands, transactional outbox relay, Inventory consumer, saga compensation, local caches, per-instance rate limiting |
| `order-query` | `order-query` | 8081 | `order_query` | `OrderPlaced` and `OrderCancelled` projections, monotonic version guard, read APIs, local cache, DLQ replay API |
| contracts | `contracts` | — | — | Shared versioned event contracts |
| load-generator (test only) | `performance` | — | — | External k6 load harness driving HTTP ports 8080/8081 |
| chaos harness (test only) | `performance/chaos` | 8474 | — | Toxiproxy network latency, packet slicing, and connection cut injection |

## Communication

- Cross-service communication is exclusively Kafka events, including
  `order-placed`, inventory failure, and `order-cancelled` flows.
- No synchronous inter-service calls (REST/gRPC) across deployables.
- The transactional outbox in `app` guarantees durable event publication.
- `order-query` consumes with dedicated consumer groups and projects into
  `order_query.order_read_model` with aggregate-version guards.
- Poison events use bounded retries and DLQs; the administrative replay path
  enforces a bounded redrive count.
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

## Verified Capabilities Through Phase 13

- Transactional outbox publishing with at-least-once delivery and idempotent
  consumers.
- CQRS order projections with monotonic aggregate-version protection.
- Choreographed inventory-failure compensation and API idempotency keys.
- Bounded retry, DLQ routing, controlled replay, and data reconciliation.
- Local Caffeine caches, event-driven invalidation, and `SKIP LOCKED` outbox
  coordination primitives.
- Distributed tracing context propagation, Prometheus-compatible metrics,
  load qualification, and deterministic Toxiproxy failure experiments.

## Current Topology Limits

- Docker Compose runs one `app` container and one `order-query` container by
  default; multi-replica service failover is not yet qualified.
- Kafka runs as one KRaft broker/controller with replication factor 1; broker
  failover and leader election to another broker are not yet proved.
- PostgreSQL runs as one primary instance; database replication, promotion,
  point-in-time recovery, and multi-zone availability are not yet proved.
- The Phase 13 client rate limiter stores counters in each `app` process. It
  is not a globally consistent quota across replicas.
- The Compose ingress, host, and container runtime remain unreplicated failure
  domains. The current evidence does not establish production-wide 99.9%
  availability.

## Planned Phase 14 Evolution

Phase 14 is limited to horizontally replicated application services and a
three-broker Kafka topology behind a health-aware ingress. It will qualify
replica loss, Kafka leader failover, consumer-group rebalancing, rolling
restart behavior, and reconciliation under sustained traffic. PostgreSQL HA,
orchestrator self-healing, and multi-region deployment remain later phases.

## References

- ADR-0010 — Extract the Order query side as the first service
- ADR-0011 — Monorepo module and per-service data-ownership model
- ADR-0012 — Resilience Strategy for Distributed Communication
- ADR-0013 — Observability Strategy for the Two-Service Platform
- ADR-0014 — Load-Test Strategy and Qualification Model
- ADR-0015 — Chaos Engineering, Network Fault Injection, and Distributed Failure Strategy
- ADR-0016 — Production Hardening, Security, Lifecycle Management, and Operational Alerting Strategy
- ADR-0017 — Distributed Saga and Compensating Transaction Strategy
- ADR-0018 — API Idempotency and Request Deduplication Strategy
- ADR-0019 — Event Schema Evolution and Versioning Strategy
- ADR-0020 — Adaptive Load Shedding and Rate Limiting
- ADR-0021 — Caching, Multi-Replica Scheduling, and Storage Lifecycle Strategy
- ADR-0022 — Distributed Stream Operations, DLQ Replay, and Out-of-Order Resilience
- Phase 14 plan — proposed Multi-Replica Runtime and Kafka High Availability
