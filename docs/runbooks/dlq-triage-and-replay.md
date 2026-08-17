# Runbook: Dead Letter Queue (DLQ) Triage and Safe Replay

**Severity:** P2 / P1 (depending on event volume)  
**Alert Reference:** `DeadLetterQueueArrival` (`events_dlq_total > 0`)  
**Components:** Apache Kafka (`order-placed-dlq`), `app` (Inventory consumer), `order-query` (Projection consumer)

---

## 1. Trigger & Overview

The Dead Letter Queue topic `order-placed-dlq` receives events that could not be processed by consumer groups after exhaustive exponential retries or because they were classified as non-retryable (e.g. `JacksonException`, invalid payload structure).

---

## 2. Step-by-Step Triage Procedure

### Step 2.1: Inspect DLQ Offsets and Ingested Count
Execute the offset shell to determine the count of records in `order-placed-dlq`:
```bash
docker exec hyperscale-kafka kafka-run-class org.apache.kafka.tools.GetOffsetShell \
  --bootstrap-server localhost:29092 \
  --topic order-placed-dlq --time -1
```

### Step 2.2: Extract Failure Headers and Payload
Consume the dead-lettered messages along with their error headers:
```bash
docker exec hyperscale-kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic order-placed-dlq \
  --from-beginning \
  --property print.headers=true \
  --property print.key=true \
  --max-messages 10
```

Inspect the standard Spring Kafka DLT headers:
- `kafka_dlt-original-topic`: Original topic name (`order-placed`).
- `kafka_dlt-original-partition`: Partition index (0, 1, or 2).
- `kafka_dlt-original-offset`: Original offset before routing.
- `kafka_dlt-exception-fqcn`: Fully qualified class name of the exception.
- `kafka_dlt-exception-message`: Detailed exception message / root cause.

---

## 3. Failure Classification & Root Cause Analysis

| Exception Class | Root Cause | Action Required |
|---|---|---|
| `com.fasterxml.jackson.core.JacksonException` | Malformed JSON / serialization corruption | Non-retryable bug. Fix payload generator or update consumer deserializer. |
| `java.lang.IllegalArgumentException` | Domain validation failure (e.g. invalid status) | Non-retryable. Verify schema version compatibility across deployables. |
| `org.springframework.dao.DataAccessException` | Transient database connection issue | Transient. Check database health and proceed to replay. |

---

## 4. Safe Replay Procedure

Once the upstream bug is fixed or database availability is restored, replay messages back to `order-placed`:

1. **Verify Consumer Idempotency:**
   Both `inventory` and `order-query` consumers are idempotent (keyed on `orderId`). Replaying will safely update or skip already-applied records without double-deducting inventory.
2. **Re-publish Payload to Original Topic:**
   ```bash
   # Re-publish extracted payload to order-placed topic using the orderId as key
   echo '<ORDER_ID>:<PAYLOAD_JSON>' | docker exec -i hyperscale-kafka kafka-console-producer \
     --bootstrap-server localhost:29092 \
     --topic order-placed \
     --property "parse.key=true" \
     --property "key.separator=:"
   ```
3. **Verify Read Model Projection & Inventory Allocation:**
   ```bash
   curl -s http://127.0.0.1:8081/orders/<ORDER_ID> | jq .
   ```
4. **Execute Data Reconciliation:**
   ```bash
   bash performance/scripts/reconcile-data.sh
   ```
