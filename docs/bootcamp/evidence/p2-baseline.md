# Phase 2 — Catalog API Baseline Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Concurrency: 10 users, ramp-up: 1s, duration: 5s.

| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|---:|---:|
| GET /catalog/products?page=0&size=20 | 1.82 | 2.46 | 5.91 | 5666.40 | 0.00 |
| GET /catalog/products/{id} | 0.67 | 0.95 | 3.73 | 14860.80 | 0.00 |
| GET /catalog/products?query=Product&page=0&size=20 | 1.73 | 2.26 | 5.08 | 6057.80 | 0.00 |
| GET /catalog/products/{id}/availability | 0.65 | 0.93 | 3.81 | 15143.00 | 0.00 |

Raw totals:
- GET /catalog/products?page=0&size=20: 28332 total, 28332 successful, 0 failed
- GET /catalog/products/{id}: 74304 total, 74304 successful, 0 failed
- GET /catalog/products?query=Product&page=0&size=20: 30289 total, 30289 successful, 0 failed
- GET /catalog/products/{id}/availability: 75715 total, 75715 successful, 0 failed
