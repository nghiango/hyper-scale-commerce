# Kafka Broker Down

## Symptoms

- Actuator health reports `kafka: DOWN` on `app` and `order-query`.
- `POST /orders` in `app` may return `5xx` once the outbox relay detects it cannot publish.
- `outbox_relay_lag` increases as events accumulate in `order.outbox_events`.
- `kafka_consumer_lag` grows because `order-query` and `inventory` consumers cannot poll.

## Metrics

| Metric | Expected | Problematic |
|---|---|---|
| `/actuator/health` `kafka` | `UP` | `DOWN` |
| `events_published_total` | increments | flat |
| `events_consumed_total` | increments | flat |
| `outbox_relay_lag` | near 0 | increasing |

## Log Queries

```bash
# Kafka connection errors in app logs
cat app.log | jq 'select(.message | contains("Kafka"))'
cat app.log | jq 'select(.message | contains("outbox"))'

# Consumer connection errors in order-query logs
cat order-query.log | jq 'select(.message | contains("Consumer"))'
```

Trace fields to look for: `traceId`, `correlationId`, `service`.

## Triage

1. Verify the Kafka container/process is running:
   ```bash
   docker compose ps kafka
   ```
2. Check network connectivity on port `9092` / `29092`.
3. Confirm both services report `kafka: DOWN` in actuator health.

## Recovery

1. Start or restart the Kafka broker / container.
2. Wait for actuator health to report `kafka: UP` on both services.
3. The outbox relay will drain `order.outbox_events` automatically.
4. Consumers will reconnect to their group and catch up from committed offsets.
5. Verify `events_published_total` and `events_consumed_total` resume incrementing and `outbox_relay_lag` / `kafka_consumer_lag` return to normal.
