# Phase 2 — SLO Verification Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Load test: ramp-up 1s, duration 5s.

| SLO | Endpoint | Concurrency | p95 (ms) | RPS | Error rate | Result |
|---|---|---:|---:|---:|---:|---|
| p95 < 100ms at 100 RPS | GET /catalog/products/{id} | 100 | 15.11 | 16457.60 | 0.00 | PASS |
| p95 < 200ms at 100 RPS | GET /catalog/products | 100 | 33.91 | 8365.60 | 0.00 | PASS |
| p95 < 300ms at 50 RPS | GET /catalog/products?query=Product | 50 | 13.48 | 8511.20 | 0.00 | PASS |
| p95 < 300ms at 500 RPS | GET /catalog/products/{id} (5x spike) | 500 | 120.28 | 10054.00 | 0.00 | PASS |
