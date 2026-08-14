# ADR-0006: Kafka as the Event Broker

- Status: Accepted
- Date: 2026-08-13
- Phase: 3 — Event-Driven Architecture

## Context

`docs/constitution.md` defines the engineering evolution and stage 4 is
Event-Driven Architecture. The constitution's distributed-systems rules (§5)
require that whenever asynchronous processing is introduced, messages must be
durable, consumers must be idempotent, failures must be observable, retries
must be bounded, poison messages must be handled, and eventual consistency must
be explicit.

The current platform is a synchronous modular monolith with a single bounded
context (Catalog). Phase 3 introduces the first asynchronous flow: an **Order**
context publishes `OrderPlaced`, and an **Inventory** context consumes it to
record reservations. A message broker must be chosen before any messaging code
is written.

## Alternatives Considered

1. **Kafka (chosen)** — durable, replayable, partitioned log; mature Spring
   integration (`spring-kafka`); first-class Testcontainers support; consumer
   groups and lag metrics are built in. Kafka is explicitly allowed by
   `docs/bootcamp/current-phase.md` for Phase 3.
2. **PostgreSQL LISTEN/NOTIFY** — no message durability, no replay, no
   partitioning, and notifications are lost when no listener is connected. Not
   suitable for reliable event delivery.
3. **Polling publisher without a broker** — a poller that reads the outbox and
   calls consumers over HTTP. Avoids a broker but re-implements broker
   semantics (consumer groups, offsets, replay) with custom code.
4. **Redis Streams** — Redis is forbidden until later phases, and streams do
   not provide the same replay and consumer-group guarantees as Kafka for this
   use case.

## Decision

Adopt **Kafka** as the event broker for Phase 3:

- Single-node Kafka running via Docker Compose for local development.
- Testcontainers Kafka for integration tests.
- `spring-kafka` for producer and consumer support.
- At-least-once delivery with idempotent consumers (dedupe by event id).
- Events are JSON payloads with a `version` field for schema evolution.
- Topic naming convention: `<domain-event-name>` (e.g., `order-placed`).

Rationale: Kafka satisfies every constitution rule for asynchronous processing
with minimal custom code, is already whitelisted for this phase, and provides
the durable, replayable backbone that later phases (CQRS, service extraction)
will build on.

## Operational Cost

- A Kafka service in `compose.yaml` and its healthcheck.
- `spring-kafka` dependency and producer/consumer configuration.
- Topic lifecycle and consumer-group management.
- Testcontainers Kafka adds startup time to integration tests.

## Failure Modes

- **Broker unavailable:** the outbox buffers events; no messages are lost.
  Publishing resumes when the broker returns.
- **Consumer down:** consumer lag grows and is observable via metrics.
- **Duplicate delivery:** at-least-once semantics; consumers must be idempotent.
- **Poison messages:** routed to a dead-letter topic and observable.

## Consequences

- P3-02 adds Kafka to local infrastructure and tests.
- P3-04 (Order) publishes `OrderPlaced` via the outbox relay.
- P3-05 (Inventory) consumes `OrderPlaced` idempotently.
- No other broker technology may be introduced without a new ADR.
