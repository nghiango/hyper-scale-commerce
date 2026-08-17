# Kafka High Availability Topology & Operational Guide

## Overview

In Phase 14 (ADR-0023), the event messaging transport evolves from a single Kafka broker (RF=1) to a deterministic three-node Apache Kafka KRaft cluster running with Replication Factor $RF=3$ and minimum In-Sync Replicas $\text{min.insync.replicas}=2$.

```text
                     Client / Service Layer
                                |
             +------------------+------------------+
             |                  |                  |
             v                  v                  v
       kafka-1 (:29092)   kafka-2 (:29093)   kafka-3 (:29094)
       [Node ID 1]        [Node ID 2]        [Node ID 3]
       Broker+Controller  Broker+Controller  Broker+Controller
             \                  |                  /
              +-----------------+-----------------+
                                |
                   KRaft Quorum (Voters: 1, 2, 3)
                   Topics: RF=3, min.insync.replicas=2
```

---

## 1. Cluster Architecture & Parameters

### Node Configuration
- **Nodes:** 3 KRaft instances (`kafka-1`, `kafka-2`, `kafka-3`).
- **Roles:** Combined `broker,controller` mode.
- **Cluster ID:** `5L6g3nShT-eMCtK--X86sw` (fixed across all nodes).
- **Quorum Voters:** `1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093`.
- **Listeners:**
  - `PLAINTEXT://0.0.0.0:9092` (internal Docker network communication).
  - `CONTROLLER://0.0.0.0:9093` (inter-controller quorum metadata).
  - `PLAINTEXT_HOST` (mapped to host ports `29092`, `29093`, `29094` for tooling/testing).

### Durability & Topic Policies
- **Replication Factor ($RF$):** 3 (all partitions replicated on all 3 brokers).
- **Min In-Sync Replicas (`min.insync.replicas`):** 2.
- **Offsets / Transaction State Log Replication:** 3.
- **Unclean Leader Election:** Explicitly disabled (`unclean.leader.election.enable=false`).
- **Auto Topic Creation:** Disabled (`auto.create.topics.enable=false`) to ensure all topics follow approved durability specifications.

---

## 2. Topic Catalog

| Topic Name | Partitions | Replication Factor | Min ISR | Description |
|---|---|---|---|---|
| `order-placed` | 3 | 3 | 2 | Primary order creation business event |
| `order-placed-dlq` | 3 | 3 | 2 | Dead-letter queue for exhausted `order-placed` processing |
| `order-cancelled` | 3 | 3 | 2 | Order cancellation / saga compensation event |
| `order-cancelled-dlq` | 3 | 3 | 2 | Dead-letter queue for exhausted `order-cancelled` processing |
| `inventory-failed` | 3 | 3 | 2 | Out-of-stock / reservation failure event |
| `inventory-failed-dlq` | 3 | 3 | 2 | Dead-letter queue for inventory failure processing |
| `health-check` | 3 | 3 | 2 | Operational probing & cluster failover smoke test topic |

---

## 3. Producer Durability Contract

Producers in `app` and `order-query` are configured with:
- `acks=all` (or `-1`): Requires acknowledgement from all currently in-sync replicas before returning success.
- `enable.idempotence=true`: Ensures no duplicate sequence numbers are committed during transient network retries.
- `retries=Integer.MAX_VALUE`: Producer will retry until metadata/leader recovers or transaction bounds are reached.
- `max.in.flight.requests.per.connection=5`: Safe ordering with idempotence enabled.
- **Key Affinity:** All events are partitioned by aggregate root ID (`orderId`), guaranteeing total ordering per aggregate.

---

## 4. Failure & Recovery Semantics

### Single Broker Loss ($N=1$ Failure)
1. **Detection:** Surviving controllers detect heartbeat loss within $\le 3\text{s}$.
2. **Leader Election:** For any partition where the failed broker was leader, an in-sync follower is elected as the new leader.
3. **Producer Availability:** Because 2 brokers remain in-sync, `min.insync.replicas=2` is satisfied. Outbox relay producers continue writing with `acks=all` with zero dropped records.
4. **Consumer Availability:** Consumer groups detect partition leader change and continue consuming from the new leader.
5. **Rejoin & Recovery:** When the stopped broker restarts, it syncs missed log segments, rejoins the ISR, and under-replicated partitions return to 0.

### Broker Quorum Loss ($N=2$ Failures — Negative Control)
- KRaft metadata loses quorum; topic writes fail with `NotEnoughReplicasException`.
- **Outbox Relay Safety:** Outbox relay catches the exception and retries with backoff without dropping transactions.
- **Recovery:** Once quorum is restored (at least 2 brokers up), outbox relay automatically drains buffered records to Kafka with zero data loss.

---

## 5. Operations & Makefile Targets

```bash
# Start HA Kafka cluster & Postgres
make ha-kafka-up

# Initialize durable topics with RF=3 and min.isr=2
make ha-kafka-init

# Run preflight cluster validation
make ha-kafka-preflight

# Run automated single-broker failover and ISR recovery test
make ha-kafka-verify

# Stop HA Kafka cluster
make ha-kafka-down
```
