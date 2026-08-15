# Phase 2 — SLO Verification Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Load test: ramp-up 1s, duration 5s.

| SLO | Endpoint | Concurrency | p95 (ms) | RPS | Error rate | Result |
|---|---|---:|---:|---:|---:|---|
| p95 < 100ms at 100 RPS | GET /catalog/products/{id} | 100 | 8.61 | 21435.80 | 0.00 | PASS |
| p95 < 200ms at 100 RPS | GET /catalog/products | 100 | 26.91 | 10559.40 | 0.00 | PASS |
| p95 < 300ms at 50 RPS | GET /catalog/products?query=Product | 50 | 11.51 | 10154.80 | 0.00 | PASS |
| p95 < 300ms at 500 RPS | GET /catalog/products/{id} (5x spike) | 500 | 77.92 | 13755.60 | 0.00 | PASS |
