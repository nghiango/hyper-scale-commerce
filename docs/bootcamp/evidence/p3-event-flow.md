# Phase 3 — Event-Driven Flow Report

Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.

## Flow

`POST /orders` → transactional outbox → Kafka `order-placed` → inventory reservation

| Step | Detail |
|---|---|
| POST /orders | 201, order id 1 |
| Outbox event | OrderPlaced, event id `9230bf91-eb4f-4395-a996-78e6c9460d94` |
| Kafka topic | order-placed |
| Inventory reservation | sku PERF-SKU-00001, quantity 2 |

## Timings

| Measurement | Value |
|---|---:|
| POST /orders latency | 18.4 ms |
| End-to-end (POST → reservation) | 507.1 ms |

## Idempotency

The OrderPlaced event was replayed on the topic; the reservation count remained 1.
