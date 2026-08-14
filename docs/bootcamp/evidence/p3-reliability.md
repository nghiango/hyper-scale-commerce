# Phase 3 — Reliability and Observability Report

Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.

## Bounded retries and dead-letter queue

Configuration: `DefaultErrorHandler` with `FixedBackOff(1000ms, 3)` and a
`DeadLetterPublishingRecoverer` routing to `order-placed-dlq`.

Failure test: a poison message (missing `eventId`) was published to
`order-placed`. The inventory consumer failed on parse, retried 3 times with
1s backoff, then the message was routed to `order-placed-dlq`.

| Test | Result |
|---|---|
| `InventoryFailureTest.poison message lands in the DLQ after bounded retries` | PASS |
| `InventoryConsumerIntegrationTest.consumes OrderPlaced and records reservations idempotently` | PASS |

## Event pipeline metrics

Metrics registered and verified at `/actuator/prometheus`
(`ApplicationIntegrationTest.event pipeline metrics are exposed` PASS):

| Metric | Source | Tags |
|---|---|---|
| `events_published_total` | Outbox relay | `topic` |
| `events_consumed_total` | Inventory consumer | `event_type`, `outcome` (processed/duplicate) |
| `events_dlq_total` | DLQ recoverer | `topic` |
| `kafka_consumer_lag` | Kafka consumer metrics (`records-lag-max`) | — |
| `outbox_relay_lag` | Outbox repository | — |

## Verification

- `make verify` — BUILD SUCCESSFUL (40 integration tests).
