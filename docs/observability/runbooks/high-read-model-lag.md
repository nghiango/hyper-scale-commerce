# High Read Model Lag

## Symptoms

- `order_read_model_lag_seconds` is increasing.
- `GET /orders/{id}` returns `404` for an order that was successfully placed.
- `kafka_consumer_lag` for the `order-query` consumer group is growing.

## Metrics

| Metric | Expected | Problematic |
|---|---|---|
| `order_read_model_lag_seconds` | < 1s | > 5s and climbing |
| `kafka_consumer_lag` | low | increasing |
| `events_consumed_total{consumer="order-query",outcome="processed"}` | increments per event | flat |

## Log Queries

```bash
# order-query projection consumer logs
docker compose logs -f order-query | grep 'Consumed record from order-placed'

# Trace and correlation context
cat order-query.log | jq 'select(.message | contains("Consumed record"))'
```

Trace fields to look for: `traceId`, `spanId`, `correlationId`, `service`.

## Triage

1. Check `events_consumed_total` to see if the consumer is processing or failing.
2. Check `events_dlq_total{topic="order-placed-dlq"}` for poison messages.
3. Query the read model to confirm the projection is behind:
   ```sql
   SELECT MAX(order_id) FROM order_query.order_read_model;
   ```
4. If `kafka_consumer_lag` is high and `events_consumed_total` is flat, the consumer may be stuck or the broker is slow.

## Recovery

1. If a poison message is blocking the consumer, follow `poison-message.md`.
2. If the broker is slow, restore Kafka and wait for the consumer to catch up.
3. To rebuild the read model from scratch:
   - Stop `order-query`.
   - Truncate `order_query.order_read_model`.
   - Reset consumer group offsets to earliest.
   - Restart `order-query`.
4. Verify `order_read_model_lag_seconds` returns to the SLO and `GET /orders/{id}` succeeds.
