# Runbook: Transactional Outbox Backlog Recovery & Consumer Lag Remediation

**Severity:** P2  
**Alert References:** 
- `OutboxRelayBacklogAging` (`outbox_unpublished_oldest_age_seconds > 5.0`)
- `KafkaConsumerLagHigh` (`kafka_consumer_lag > 100`)  
**Components:** PostgreSQL (`"order".outbox_events`), `app` (Outbox Relay), Apache Kafka

---

## 1. Trigger & Overview

The Transactional Outbox pattern guarantees at-least-once event delivery by persisting domain events inside PostgreSQL within the order creation database transaction. The background relay asynchronously polls unpublished events and publishes them to Kafka.

When Kafka is partitioned, database transactions take longer, or traffic spikes exceed relay throughput, an outbox backlog accumulates.

---

## 2. Diagnostics & Investigation

### Step 2.1: Query Outbox Backlog Statistics
Connect to PostgreSQL and inspect uncommitted / unpublished outbox events:
```sql
SELECT 
    count(*) AS unpublished_count,
    min(created_at) AS oldest_pending_event,
    now() - min(created_at) AS oldest_age
FROM "order".outbox_events
WHERE published_at IS NULL;
```

### Step 2.2: Check Outbox Polling Thread & Lock Contention
Check if the outbox relay background thread is active or blocked:
```sql
SELECT pid, state, query_start, wait_event_type, wait_event, query 
FROM pg_stat_activity 
WHERE query LIKE '%outbox_events%' AND pid <> pg_backend_pid();
```

### Step 2.3: Check Kafka Broker Reachability
Verify Kafka connectivity from the `app` container:
```bash
docker exec hyperscale-app curl -s http://localhost:8080/actuator/health | jq .components.kafka
```

---

## 3. Remediation Actions

### Action A: Kafka Broker Unreachable / Network Issue
1. Check Kafka container health:
   ```bash
   docker inspect --format='{{.State.Health.Status}}' hyperscale-kafka
   ```
2. If Kafka is down, restart the broker:
   ```bash
   docker restart hyperscale-kafka
   ```
3. Once Kafka returns, the outbox relay will automatically resume publishing pending events without manual intervention.

### Action B: High Traffic Spike Backlog Accumulation
If traffic surges exceed standard relay throughput (e.g. $>2,000$ RPS sustained):
1. The outbox relay is configured with batch claim limit:
   ```yaml
   app:
     outbox:
       relay-interval-ms: 50
       claim-limit: 1000
   ```
2. Monitor backlog drain rate:
   ```bash
   watch -n 1 'docker exec hyperscale-postgres psql -U hyperscale -d hyperscale -c "SELECT count(*) FROM \"order\".outbox_events WHERE published_at IS NULL;"'
   ```

### Action C: Outbox Table Maintenance
Over time, historical published events can accumulate. Clean up published events older than 7 days during maintenance windows:
```sql
DELETE FROM "order".outbox_events 
WHERE published_at IS NOT NULL 
  AND published_at < now() - INTERVAL '7 days';
VACUUM ANALYZE "order".outbox_events;
```
