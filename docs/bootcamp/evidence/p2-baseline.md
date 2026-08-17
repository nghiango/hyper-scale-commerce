# Phase 2 — Catalog API Baseline Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Concurrency: 10 users, ramp-up: 1s, duration: 5s.

| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|---:|---:|
| GET /catalog/products?page=0&size=20 | 0.35 | 0.53 | 0.84 | 26182.20 | 0.00 |
| GET /catalog/products/{id} | 0.34 | 0.49 | 0.67 | 27805.40 | 0.00 |
| GET /catalog/products?query=Product&page=0&size=20 | 0.35 | 0.52 | 0.77 | 27404.20 | 0.00 |
| GET /catalog/products/{id}/availability | 0.34 | 0.50 | 0.70 | 27964.40 | 0.00 |

Raw totals:
- GET /catalog/products?page=0&size=20: 130911 total, 130911 successful, 0 failed
- GET /catalog/products/{id}: 139027 total, 139027 successful, 0 failed
- GET /catalog/products?query=Product&page=0&size=20: 137021 total, 137021 successful, 0 failed
- GET /catalog/products/{id}/availability: 139822 total, 139822 successful, 0 failed
