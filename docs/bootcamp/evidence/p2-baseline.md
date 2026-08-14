# Phase 2 — Catalog API Baseline Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Concurrency: 10 users, ramp-up: 1s, duration: 5s.

| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|---:|---:|
| GET /catalog/products?page=0&size=20 | 1.73 | 2.38 | 3.14 | 6095.80 | 0.00 |
| GET /catalog/products/{id} | 0.58 | 0.79 | 1.31 | 18025.60 | 0.00 |
| GET /catalog/products?query=Product&page=0&size=20 | 1.70 | 2.16 | 3.05 | 6297.80 | 0.00 |
| GET /catalog/products/{id}/availability | 0.57 | 0.79 | 1.35 | 18476.80 | 0.00 |

Raw totals:
- GET /catalog/products?page=0&size=20: 30479 total, 30479 successful, 0 failed
- GET /catalog/products/{id}: 90128 total, 90128 successful, 0 failed
- GET /catalog/products?query=Product&page=0&size=20: 31489 total, 31489 successful, 0 failed
- GET /catalog/products/{id}/availability: 92384 total, 92384 successful, 0 failed
