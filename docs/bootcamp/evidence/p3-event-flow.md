# Phase 3 — Event-Driven Flow Report

Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.

## Flow

`POST /orders` → transactional outbox → Kafka `order-placed` → inventory reservation

| Step | Detail |
|---|---|
| POST /orders | 201, order id 1 |
| Outbox event | OrderPlaced, event id `7be5ab36-a476-46ef-84a4-d3de60f6ce5c` |
| Kafka topic | order-placed |
| Inventory reservation | sku PERF-SKU-00001, quantity 2 |

## Timings

| Measurement | Value |
|---|---:|
| POST /orders latency | 21.9 ms |
| End-to-end (POST → reservation) | 1513.6 ms |

## Idempotency

The OrderPlaced event was replayed on the topic; the reservation count remained 1.
