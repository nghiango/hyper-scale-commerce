# P17-05 Completion Record: PostgreSQL Read/Write Routing

- **Task:** P17-05 — PostgreSQL Read/Write Splitting & Dynamic DataSource Routing
- **Status:** PASSED
- **Date:** 2026-08-17

## Implemented

- Separate primary and replica HikariCP pools in `app` and `order-query`.
- Transaction-aware `AbstractRoutingDataSource`, wrapped in
  `LazyConnectionDataSourceProxy`, defaults writes and unclassified access to
  primary and routes only read-only transactions to a healthy replica.
- Fail-closed startup fencing and scheduled PostgreSQL replay-lag sampling;
  unhealthy replicas or lag above 100 ms route reads to primary.
- Read-only transaction boundaries on catalog and Order Query read operations.
- Kubernetes multi-host JDBC configuration using `targetServerType=primary`
  and strict `targetServerType=secondary` for deterministic read offloading.

## Verification

- Routing and lag-monitor unit tests: PASSED.
- Two real PostgreSQL containers with distinct marker data proved:
  - write transaction -> `PRIMARY`;
  - read-only transaction at 10 ms recorded lag -> `REPLICA`;
  - read-only transaction at 101 ms recorded lag -> `PRIMARY`.
- Full `./gradlew test`: PASSED.
- Helm render, Redis packaging, authentication, persistence, and wiring harness:
  PASSED.

## Operational Safety

- Replica eligibility starts false and becomes true only after a successful lag
  sample.
- A lag-query error immediately fences the replica.
- Primary-only development remains supported by the default local
  `preferSecondary` URL. Kubernetes uses strict secondary targeting so the
  replica pool cannot silently retain a primary connection.
