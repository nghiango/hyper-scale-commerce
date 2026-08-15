# High Outbox Relay Lag

## Symptoms

- `outbox_relay_lag` is increasing or remains above the SLO threshold.
- `events_published_total{topic="order-placed"}` has stopped incrementing.
- A `POST /orders` returns `201 Created` but the order is not yet visible in `order-query`.

## Metrics

| Metric | Expected | Problematic |
|---|---|---|
| `outbox_relay_lag` | near 0 | > 5s and climbing |
| `events_published_total{topic="order-placed"}` | increments with each order | flat |
| `kafka_consumer_lag` | low | increasing because no new events are published |

## Log Queries

```bash
# Outbox relay activity
docker compose logs -f app | grep -i 'outbox'

# Structured JSON filter
cat app.log | jq 'select(.message | contains("outbox"))'
```

Trace fields to look for: `traceId`, `correlationId`, `service`.

## Triage

1. Check actuator health on `app` for `kafka` status.
2. Query the `order.outbox_events` table:
   ```sql
   SELECT COUNT(*) FROM order.outbox_events WHERE published_at IS NULL;
   ```
3. If the count is growing and Kafka health is `DOWN`, the broker is unreachable.
4. If Kafka is `UP` but lag is still high, check relay logs for serialization or connection errors.

## Recovery

1. Restore the Kafka broker / container.
2. Wait for actuator health `kafka: UP` on `app`.
3. The outbox relay will automatically drain `order.outbox_events`.
4. Verify `events_published_total` and `outbox_relay_lag` return to normal.
