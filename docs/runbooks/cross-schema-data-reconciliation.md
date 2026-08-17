# Runbook: Cross-Schema Data Reconciliation & Consistency Audit

**Severity:** P2 / Routine Operational Audit  
**Components:** PostgreSQL Schemas (`catalog`, `order`, `inventory`, `order_query`), Kafka Read Model Projections

---

## 1. Trigger & Overview

HyperScale Commerce maintains strong consistency within bounded contexts (via ACID transactions in PostgreSQL) and eventual consistency across contexts (via Kafka and the Transactional Outbox).

This runbook defines the automated and manual verification procedures to ensure that 100% of placed orders are correctly reflected across:
1. `"order".orders` & `"order".order_items`
2. `"order".outbox_events` (all published)
3. `"inventory".inventory_reservations` (allocated units match order items)
4. `"order_query".order_read_model` (all placed orders projected with exact status and item count)

---

## 2. Automated Reconciliation Execution

Run the certified platform data reconciliation script:
```bash
bash performance/scripts/reconcile-data.sh
```

### Expected Output:
```text
=== HyperScale Commerce Data Reconciliation Audit ===
[1/4] Verifying Order count vs Read Model count... PASS (Orders: N, ReadModel: N)
[2/4] Verifying Outbox published status... PASS (Unpublished: 0)
[3/4] Verifying Inventory reservation integrity... PASS (All items reserved)
[4/4] Verifying Read model payload integrity... PASS (Zero orphan records)
=== Reconciliation Status: 100% PASS ===
```

---

## 3. Manual Cross-Schema Reconciliation Queries

If any automated check reports a discrepancy, execute these diagnostic SQL queries:

### Query 1: Unprojected Orders Audit
Find orders present in `"order".orders` but missing in `"order_query".order_read_model`:
```sql
SELECT o.id, o.status, o.created_at
FROM "order".orders o
LEFT JOIN "order_query".order_read_model r ON o.id = r.order_id
WHERE r.order_id IS NULL;
```

### Query 2: Outbox Event Publishing Audit
Find orders whose outbox events have not been published:
```sql
SELECT o.id, e.id AS outbox_id, e.event_type, e.created_at
FROM "order".orders o
JOIN "order".outbox_events e ON o.id::text = e.aggregate_id
WHERE e.published_at IS NULL;
```

### Query 3: Inventory Reservation Audit
Verify that reserved item quantities match placed order line items:
```sql
SELECT 
    oi.order_id, 
    oi.sku, 
    oi.quantity AS ordered_qty, 
    COALESCE(ir.quantity, 0) AS reserved_qty
FROM "order".order_items oi
LEFT JOIN "inventory".inventory_reservations ir 
    ON oi.order_id = ir.order_id AND oi.sku = ir.sku
WHERE oi.quantity <> COALESCE(ir.quantity, 0);
```

---

## 4. Remediation of Unprojected Orders

If an event was lost or a consumer was skipped:
1. Locate the original event JSON from `"order".outbox_events`:
   ```sql
   SELECT payload FROM "order".outbox_events WHERE aggregate_id = '<ORDER_ID>';
   ```
2. Re-publish the event to `order-placed` topic via Kafka console producer:
   ```bash
   echo '<ORDER_ID>:<PAYLOAD_JSON>' | docker exec -i hyperscale-kafka kafka-console-producer \
     --bootstrap-server localhost:29092 \
     --topic order-placed \
     --property "parse.key=true" \
     --property "key.separator=:"
   ```
3. Re-run `bash performance/scripts/reconcile-data.sh` to confirm 100% reconciliation.
