# Runbook: Kafka 3-Broker KRaft HA Operations & Recovery

## Overview

In Phase 14 (ADR-0023, P14-02), Kafka operates as a 3-broker KRaft cluster (`kafka-1`, `kafka-2`, `kafka-3`) with fixed node IDs, quorum voting, replication factor 3, and `min.insync.replicas=2`.

This runbook guides operators through diagnosing broker outages, monitoring Under-Replicated Partitions (URP), recovering failed brokers, and restoring cluster health.

---

## 1. Fast Diagnostics Commands

### Check Cluster Quorum & Broker Status
```bash
# Check running Kafka containers
docker ps --filter "name=hyperscale-kafka" --format "table {{.Names}}\t{{.Status}}"

# Run automated cluster preflight check
make ha-kafka-preflight
```

### Describe Topics & Under-Replicated Partitions (URP)
```bash
docker exec hyperscale-kafka-1 kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe \
  --under-replicated-partitions
```
*Expected Output for Healthy Cluster:* Empty (no under-replicated partitions).

### Check Active Partition Leaders and ISR (In-Sync Replicas)
```bash
docker exec hyperscale-kafka-1 kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic order-placed
```
*Expected Output:* `ReplicationFactor: 3`, `Leader: <id>`, `Replicas: 1,2,3`, `Isr: 1,2,3`.

---

## 2. Failure Scenarios & Recovery Procedures

### Scenario A: Single Broker Failure (e.g. `kafka-1` down)
1. **Symptoms:**
   - Metric `kafka_under_replicated_partitions` rises from 0 to 3.
   - Describe topic shows `Isr: 2,3` for affected partitions.
   - System remains **100% operational** because $\text{ISR}=2 \ge \text{min.insync.replicas}=2$.
2. **Recovery:**
   - Restart the failed container:
     ```bash
     docker start hyperscale-kafka-1
     ```
   - Verify broker rejoins ISR:
     ```bash
     docker exec hyperscale-kafka-1 kafka-topics \
       --bootstrap-server localhost:9092 \
       --describe \
       --topic order-placed
     ```
   - Confirm `Isr: 1,2,3` and URP = 0.

---

### Scenario B: Kafka Quorum Loss (2 of 3 Brokers Down)
1. **Symptoms:**
   - Producers receive `NotEnoughReplicasException` when attempting to produce.
   - Spring Boot `OutboxRelay` logs warnings and pauses publishing.
   - **Data Invariant:** `POST /orders` continues accepting orders by storing events durably in PostgreSQL `order.outbox_events` within the transactional boundary. **Zero orders are lost.**
2. **Recovery:**
   - Restart the down broker containers:
     ```bash
     docker start hyperscale-kafka-1 hyperscale-kafka-2
     ```
   - Wait 10 seconds for KRaft quorum election and ISR synchronization.
   - Monitor `OutboxRelay` logs to observe automatic draining of queued events:
     ```bash
     docker logs -f hyperscale-app-1 | grep -i "outbox"
     ```
   - Verify 100% data reconciliation:
     ```bash
     bash performance/scripts/reconcile-data.sh
     ```

---

## 3. Operational Invariants & Safeguards

- **No Unclean Leader Election:** `unclean.leader.election.enable=false` prevents stale replicas from being elected leader, protecting against silent data loss or offset truncation.
- **Producer Durability:** Producers use `acks=all`, `enable.idempotence=true`, and `retries=MAX_VALUE`.
- **Automated Verification:** Run `make ha-kafka-verify` to automatically test broker kill, failover, and recovery.
