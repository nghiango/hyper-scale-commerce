# Phase 2 — SLO Verification Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Load test: ramp-up 1s, duration 5s.

| SLO | Endpoint | Concurrency | p95 (ms) | RPS | Error rate | Result |
|---|---|---:|---:|---:|---:|---|
| p95 < 100ms at 100 RPS | GET /catalog/products/{id} | 100 | 9.24 | 22918.60 | 0.00 | PASS |
| p95 < 200ms at 100 RPS | GET /catalog/products | 100 | 11.46 | 21059.40 | 0.00 | PASS |
| p95 < 300ms at 50 RPS | GET /catalog/products?query=Product | 50 | 4.34 | 22903.80 | 0.00 | PASS |
| p95 < 300ms at 500 RPS | GET /catalog/products/{id} (5x spike) | 500 | 92.13 | 12465.80 | 0.00 | PASS |
