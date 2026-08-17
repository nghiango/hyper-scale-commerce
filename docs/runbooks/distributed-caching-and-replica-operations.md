# Runbook: Distributed Caching & Read-Replica Operations

## Overview

Phase 17 (ADR-0026) defines a multi-level near-cache (L1 in-memory Caffeine + L2 distributed Redis StatefulSet) and dynamic PostgreSQL read-write transaction splitting with replication-lag fencing ($100\text{ms}$). The Phase 17 review passed on 2026-08-17 after end-to-end runtime qualification confirmed the deployed behavior; these are the verified operating procedures for the local Kubernetes baseline.

This runbook provides diagnostics, failure mitigation, and maintenance procedures for operators.

---

## 1. Quick Diagnostics

```bash
# Check Redis pod and StatefulSet status
kubectl get pods -n hyperscale -l app.kubernetes.io/component=redis
kubectl logs -n hyperscale redis-0 --tail=50

# Test Redis connection & memory statistics
kubectl exec -it -n hyperscale redis-0 -- redis-cli -a redis_secure_password info memory

# Inspect PostgreSQL streaming replication status and lag from primary
kubectl exec -it -n hyperscale postgres-ha-0 -- psql -U postgres -d hyperscale -c "
  SELECT client_addr, state, sync_state, sync_priority,
         pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS replay_lag_bytes,
         write_lag, flush_lag, replay_lag
  FROM pg_stat_replication;
"

# Check cache invalidation Kafka topic consumer lag
kubectl exec -it -n hyperscale kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group-prefix cache-invalidation

# Verify the Redis exporter from a monitoring pod
curl -fsS http://redis.hyperscale.svc.cluster.local:9121/metrics | grep '^redis_up'

# Inspect application cache, pool, and replay-lag metrics
curl -fsS http://localhost:8080/actuator/prometheus | \
  grep -E '^(hyperscale_cache_gets_total|hyperscale_cache_evictions_total|datasource_connections_active|postgres_replication_lag_seconds)'
```

| Metric | Important labels | Meaning |
|---|---|---|
| `hyperscale_cache_gets_total` | `cache`, `level`, `result` | L1/L2 hit and miss counters |
| `hyperscale_cache_evictions_total` | `cache`, `reason` | Mutation, Kafka event, TTL, or LRU evictions |
| `datasource_connections_active` | `pool` | Active primary and replica Hikari connections |
| `postgres_replication_lag_seconds` | `replica` | Latest successful standby replay-lag sample |
| `redis_up` | exporter target labels | Redis exporter connectivity state |

---

## 2. Incident Response Procedures

### Alert: `RedisNodeDown`
- **Impact:** L2 distributed cache unavailable. Near-cache operates in **fail-open** mode, falling back transparently to L1 and database read-replicas.
- **Triage:**
  1. Inspect pod events: `kubectl describe pod redis-0 -n hyperscale`
  2. Inspect storage: verify PVC `redis-data-redis-0` is bound and mounted.
  3. Restart Redis StatefulSet: `kubectl rollout restart statefulset/redis -n hyperscale`

---

### Alert: `PostgresReplicationLagHigh`
- **Impact:** Replication lag on standby read replicas exceeds $100\text{ms}$. `TransactionRoutingDataSource` automatically diverts read queries to the Patroni primary database to maintain consistency.
- **Triage:**
  1. Identify lagging replica using `pg_stat_replication`.
  2. Check replica host I/O and network bandwidth.
  3. If disk saturated, investigate long-running transactions or vacuum processes blocking WAL replay (`hot_standby_feedback`).

---

### Alert: `L2CacheMissRateSpike`
- **Impact:** Increased read load on PostgreSQL read replicas.
- **Triage:**
  1. Check if an invalidation flood occurred (e.g. bulk catalog update).
  2. Verify cache keys are not expiring prematurely due to eviction policies (`maxmemory-policy volatile-lru`).

---

## 3. Maintenance Procedures

### Invalidating Caches Across All Pods

To invalidate catalog L1 entries across every running app pod, publish a
cache-wide event to the dedicated broadcast topic. This does not flush
unrelated Redis keys:

```bash
# Send an empty-key broadcast invalidation message
kubectl exec -it -n hyperscale kafka-0 -- /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic catalog-cache-evict <<EOF
{"cacheName":"catalog_products","key":null,"originInstanceId":"ops-manual"}
{"cacheName":"catalog_list","key":null,"originInstanceId":"ops-manual"}
EOF
```

For Order Query, publish `{"cacheName":"order_query","key":null}` to
`order-cache-evict`. Consumers intentionally use unique per-pod groups, so each
pod receives the broadcast. Do not use `FLUSHALL`; cache keys share Redis with
other application state.

---

## 4. Alert Verification

Validate rules before deployment:

```bash
docker run --rm --entrypoint=promtool -v "$PWD:/workspace:ro" -w /workspace \
  prom/prometheus:v2.52.0 \
  check rules performance/monitoring/cache-replica-alerts.yml
```

Application routing and alert timing deliberately differ: reads are fenced back
to primary above 100 ms, while `PostgresReplicationLagHigh` fires only after lag
remains above 1 second for 1 minute. A missing or zero `redis_up` series triggers
`RedisNodeDown` after 1 minute.
