# Phase 2 — Catalog Tuning Report

Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.
Load test: 10 users, ramp-up 1s, duration 5s.

## Changes applied

### 1. Hikari connection pool (`app/src/main/resources/application.yml`)

- `maximum-pool-size: 20` (default was 10)
- `minimum-idle: 5`
- `connection-timeout: 5000` ms
- `idle-timeout: 600000` ms
- `max-lifetime: 1800000` ms

### 2. Tomcat threads

- **Not changed.** The HTTP layer was not identified as a bottleneck in
  `p2-profile.md`; the profile showed sub-millisecond endpoint timings.

### 3. PostgreSQL index migration

- **Not added.** The search and count queries use `ILIKE '%...%'`, which cannot
  use the existing B-tree indexes. `pg_trgm` is explicitly out of scope for this
  phase.

## Before / after

| Endpoint | p95 before (ms) | p95 after (ms) | RPS before | RPS after |
|---|---:|---:|---:|---:|
| `GET /catalog/products?page=0&size=20` | 2.22 | 2.25 | 6318.60 | 6325.00 |
| `GET /catalog/products/{id}` | 0.73 | 0.73 | 19374.40 | 19560.40 |
| `GET /catalog/products?query=Product&page=0&size=20` | 2.01 | 2.11 | 7015.20 | 6815.80 |
| `GET /catalog/products/{id}/availability` | 0.72 | 0.72 | 19925.60 | 20117.80 |

## Impact

- All endpoints remain well under the Phase 2 SLOs.
- The before/after differences are within run-to-run noise; no regression was
  observed.
- The pool of 20 connections provides headroom for the 100-user SLO scenario
  while keeping the local footprint small.
- The primary bottleneck (sequential scan on search/count) is unchanged and is
  documented in `p2-profile.md`.

## P2-06 — HTTP and JVM tuning

### Changes applied

- `server.tomcat.threads.max: 200`, `min-spare: 10`, `accept-count: 100`,
  `max-connections: 10000`, `connection-timeout: 20000` in `application.yml`.
- `server.compression.enabled: true`, `min-response-size: 1024`,
  `mime-types: application/json` in `application.yml`.
- `bootRun` JVM args `-Xms256m -Xmx512m -XX:+UseG1GC` in `build.gradle.kts`.
- Test JVM `maxHeapSize = 1g` and `-XX:+UseG1GC` in `build.gradle.kts`.

### Before / after (P2-05 baseline vs P2-06)

| Endpoint | p95 before (ms) | p95 after (ms) | RPS before | RPS after |
|---|---:|---:|---:|---:|
| `GET /catalog/products?page=0&size=20` | 2.25 | 2.49 | 6325.00 | 6349.00 |
| `GET /catalog/products/{id}` | 0.73 | 0.73 | 19560.40 | 19306.00 |
| `GET /catalog/products?query=Product&page=0&size=20` | 2.11 | 1.92 | 6815.80 | 6742.80 |
| `GET /catalog/products/{id}/availability` | 0.72 | 0.70 | 20117.80 | 20382.20 |

### Impact

- All endpoints remain well under the Phase 2 SLOs.
- Differences are within run-to-run noise; no regression was observed.
- HTTP compression reduces JSON payload size; G1GC is the JDK 21 default and is
  now explicit for both `bootRun` and test JVMs.
