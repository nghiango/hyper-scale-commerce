# Phase 4 — CQRS End-to-End Report

Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.

## Flow

`POST /orders` → transactional outbox → Kafka `order-placed` → `order-query` projection → `order.order_read_model` → `GET /orders/{id}`

| Step | Detail |
|---|---|
| POST /orders | 201, order id 1 |
| Outbox event | OrderPlaced |
| Projection | read model row created for order 1 |
| GET /orders/1 | 200, served from `order.order_read_model` |

## Timings

| Measurement | Value |
|---|---:|
| POST /orders latency | 114.0 ms |
| End-to-end (POST → read model visible) | 1023.4 ms |

## Idempotency

The OrderPlaced event was replayed on the topic; the read model row count remained 1 and the row was unchanged.

## Query endpoint p95 under concurrent load

Load: 20 concurrent users, ramp-up 1s, duration 3s.

| Endpoint | p95 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|
| GET /orders/{id} | 1.44 | 21272.33 | 0.00 |
| GET /orders | 1.91 | 16008.67 | 0.00 |

## Catalog SLO re-verification

Spot check with 5 seeded products: GET /catalog/products/{id} at 20 concurrent users → p95 1.44 ms, error rate 0.00.
The full catalog SLO verification (`CatalogSloVerificationTest`) re-runs in `make verify`; see `p2-slo-verification.md`.
