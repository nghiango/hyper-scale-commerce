# P17-06 Completion Record: Cache and Replica Observability

- **Task:** P17-06 — Cache & Replica Observability, Alerts & Runbooks
- **Status:** PASSED
- **Date:** 2026-08-17

## Implemented

- L1/L2 get counters with cache, level, and hit/miss result labels in both
  services.
- Cache eviction counters with mutation, broadcast event, TTL, and LRU reasons.
- Active-connection gauges for primary and replica Hikari pools.
- PostgreSQL replay-lag gauge in seconds from the routing fence's latest sample.
- Authenticated Redis exporter sidecar, metrics service port, scrape annotations,
  resource limits, and NetworkPolicy access for monitoring pods.
- Four alert rules for Redis availability, L2 miss spikes, replication lag, and
  invalidation consumer lag over the real dedicated topics.
- Operator diagnostics, alert semantics, cache invalidation procedures, and
  rule-validation commands in the Phase 17 runbook.

## Verification

- Focused cache and datasource metric unit tests: PASSED.
- App Spring startup and actuator metric contract integration test: PASSED.
- Order Query Spring startup integration test: PASSED.
- Full `./gradlew test`: PASSED.
- Helm Redis/exporter/NetworkPolicy render and runtime harness: PASSED.
- Prometheus 2.52.0 `promtool check rules`: `SUCCESS: 4 rules found`.

## Alert Thresholds

- Replica routing fences reads above 100 ms immediately after sampling.
- `PostgresReplicationLagHigh` warns above 1 second sustained for 1 minute.
- `L2CacheMissRateSpike` warns above a 40% five-minute miss rate for 3 minutes.
- `RedisNodeDown` covers both `redis_up == 0` and an absent exporter series.
