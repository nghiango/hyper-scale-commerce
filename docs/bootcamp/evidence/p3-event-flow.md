# Phase 3 — Event-Driven Flow Report

Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.

## Flow

`POST /orders` → transactional outbox → Kafka `order-placed` → inventory reservation

| Step | Detail |
|---|---|
| POST /orders | 201, order id 1 |
| Outbox event | OrderPlaced, event id `456da6b3-c8b4-4f6e-a4b4-28240347717d` |
| Kafka topic | order-placed |
| Inventory reservation | sku PERF-SKU-00001, quantity 2 |

## Timings

| Measurement | Value |
|---|---:|
| POST /orders latency | 36.7 ms |
| End-to-end (POST → reservation) | 1521.9 ms |

## Idempotency

The OrderPlaced event was replayed on the topic; the reservation count remained 1.
