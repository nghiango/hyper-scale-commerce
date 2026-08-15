# Phase 2 — Catalog API Baseline Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Concurrency: 10 users, ramp-up: 1s, duration: 5s.

| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|---:|---:|
| GET /catalog/products?page=0&size=20 | 1.63 | 2.10 | 2.87 | 6528.20 | 0.00 |
| GET /catalog/products/{id} | 0.54 | 0.73 | 1.18 | 19479.60 | 0.00 |
| GET /catalog/products?query=Product&page=0&size=20 | 1.61 | 2.11 | 2.74 | 6643.40 | 0.00 |
| GET /catalog/products/{id}/availability | 0.53 | 0.73 | 1.14 | 19779.80 | 0.00 |

Raw totals:
- GET /catalog/products?page=0&size=20: 32641 total, 32641 successful, 0 failed
- GET /catalog/products/{id}: 97398 total, 97398 successful, 0 failed
- GET /catalog/products?query=Product&page=0&size=20: 33217 total, 33217 successful, 0 failed
- GET /catalog/products/{id}/availability: 98899 total, 98899 successful, 0 failed
