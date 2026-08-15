# Phase 2 — Catalog API Baseline Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Concurrency: 10 users, ramp-up: 1s, duration: 5s.

| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|---:|---:|
| GET /catalog/products?page=0&size=20 | 2.00 | 2.70 | 6.50 | 5158.00 | 0.00 |
| GET /catalog/products/{id} | 0.71 | 1.03 | 3.88 | 13847.80 | 0.00 |
| GET /catalog/products?query=Product&page=0&size=20 | 1.84 | 2.38 | 5.41 | 5680.20 | 0.00 |
| GET /catalog/products/{id}/availability | 0.66 | 0.94 | 3.81 | 14919.00 | 0.00 |

Raw totals:
- GET /catalog/products?page=0&size=20: 25790 total, 25790 successful, 0 failed
- GET /catalog/products/{id}: 69239 total, 69239 successful, 0 failed
- GET /catalog/products?query=Product&page=0&size=20: 28401 total, 28401 successful, 0 failed
- GET /catalog/products/{id}/availability: 74595 total, 74595 successful, 0 failed
