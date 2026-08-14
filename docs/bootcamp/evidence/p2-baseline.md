# Phase 2 — Catalog API Baseline Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Concurrency: 10 users, ramp-up: 1s, duration: 5s.

| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|---:|---:|
| GET /catalog/products?page=0&size=20 | 1.87 | 2.60 | 3.37 | 5637.40 | 0.00 |
| GET /catalog/products/{id} | 0.57 | 0.78 | 1.29 | 18435.00 | 0.00 |
| GET /catalog/products?query=Product&page=0&size=20 | 1.68 | 2.03 | 2.76 | 6444.20 | 0.00 |
| GET /catalog/products/{id}/availability | 0.57 | 0.77 | 1.18 | 18770.60 | 0.00 |

Raw totals:
- GET /catalog/products?page=0&size=20: 28187 total, 28187 successful, 0 failed
- GET /catalog/products/{id}: 92175 total, 92175 successful, 0 failed
- GET /catalog/products?query=Product&page=0&size=20: 32221 total, 32221 successful, 0 failed
- GET /catalog/products/{id}/availability: 93853 total, 93853 successful, 0 failed
