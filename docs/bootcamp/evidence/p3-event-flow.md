# Phase 3 — Event-Driven Flow Report

Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.

## Flow

`POST /orders` → transactional outbox → Kafka `order-placed` → inventory reservation

| Step | Detail |
|---|---|
| POST /orders | 201, order id 1 |
| Outbox event | OrderPlaced, event id `85d95343-34a3-4302-b648-d3d44eb0b38d` |
| Kafka topic | order-placed |
| Inventory reservation | sku PERF-SKU-00001, quantity 2 |

## Timings

| Measurement | Value |
|---|---:|
| POST /orders latency | 22.2 ms |
| End-to-end (POST → reservation) | 1511.4 ms |

## Idempotency

The OrderPlaced event was replayed on the topic; the reservation count remained 1.
