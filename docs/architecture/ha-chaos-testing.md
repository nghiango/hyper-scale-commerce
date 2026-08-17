# High Availability Chaos Engineering & Failure Experiment Guide

## Overview

In Phase 14 (P14-06), the chaos engineering harness is extended to perform deterministic, automated failure injection across horizontally replicated services and the three-node Kafka KRaft cluster.

---

## 1. Supported Failure Scenarios

| Scenario Name | Fault Injected | Expected Invariant / Hypothesis | Recovery & Verification |
|---|---|---|---|
| `app-replica-loss` | `SIGKILL` on `app-1` under load | HAProxy removes dead replica ($\le 5\text{s}$); `app-2` handles write traffic without request loss. | Restart `app-1`; re-admitted to HAProxy; 100% reconciliation. |
| `query-replica-loss` | `SIGKILL` on `order-query-1` | `order-query-2` serves read queries; Kafka triggers consumer group rebalance. | Restart `order-query-1`; partitions rebalanced; projections consistent. |
| `rolling-restart` | Sequential graceful restart of `app-1` then `app-2` | Spring Boot graceful drain (30s timeout) ensures zero 502/504 errors. | Both instances healthy; 100% reconciliation. |
| `kafka-leader-loss` | `SIGKILL` on active Kafka partition leader broker | KRaft elects in-sync follower within $\le 5\text{s}$; `acks=all` writes continue on ISR=2. | Restart broker; rejoins ISR; 0 under-replicated partitions. |
| `kafka-quorum-loss-control` | Stop 2 of 3 Kafka brokers (Negative Control) | `POST /orders` continues accepting orders by storing in PostgreSQL outbox; outbox relay retries with backoff. | Restart brokers; outbox relay drains buffered rows with 0 lost orders. |
| `postgres-loss-control` | Stop PostgreSQL Primary (Negative Control) | Honest failure: health probes report unready; HAProxy returns 503; no uncommitted data acknowledged. | Restart PostgreSQL; health checks recover. |

---

## 2. Safety Controls & Invariants

1. **Target Allow-List Validation:** All target containers are validated against `validate_target_safety` in `performance/chaos/lib/common.sh` before any stop/kill signal.
2. **Deterministic Cleanup Trap:** Exit traps (`trap cleanup_ha EXIT INT TERM`) unconditionally restart all stopped cluster containers upon completion or interruption.
3. **Automated Reconciliation Gate:** Every scenario run must conclude with automated SQL reconciliation (`reconcile-data.sh`) asserting 0 unpublished outbox rows, 0 duplicate read-model rows, and 0 DLQ messages.

---

## 3. Makefile Targets

```bash
# Run HA Chaos Smoke test (rapid failover validation)
make ha-chaos-smoke

# Run full HA Replica Failure scenario
make ha-chaos-replica

# Run active Kafka Leader Loss scenario
make ha-chaos-kafka-leader

# Run negative control: Kafka Quorum Loss
make ha-chaos-quorum-loss

# Run negative control: PostgreSQL Primary Loss
make ha-chaos-postgres-loss
```
