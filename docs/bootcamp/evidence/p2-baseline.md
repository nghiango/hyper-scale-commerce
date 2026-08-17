# Phase 2 — Catalog API Baseline Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Concurrency: 10 users, ramp-up: 1s, duration: 5s.

| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|---:|---:|
| GET /catalog/products?page=0&size=20 | 0.37 | 0.55 | 1.13 | 25306.80 | 0.00 |
| GET /catalog/products/{id} | 0.38 | 0.57 | 1.95 | 24361.40 | 0.00 |
| GET /catalog/products?query=Product&page=0&size=20 | 0.37 | 0.55 | 1.00 | 25742.80 | 0.00 |
| GET /catalog/products/{id}/availability | 0.37 | 0.56 | 0.93 | 25869.00 | 0.00 |

Raw totals:
- GET /catalog/products?page=0&size=20: 126534 total, 126534 successful, 0 failed
- GET /catalog/products/{id}: 121807 total, 121807 successful, 0 failed
- GET /catalog/products?query=Product&page=0&size=20: 128714 total, 128714 successful, 0 failed
- GET /catalog/products/{id}/availability: 129345 total, 129345 successful, 0 failed
