# Poison Message in DLQ

## Symptoms

- `events_dlq_total{topic="order-placed-dlq"}` increments.
- `events_consumed_total{consumer="order-query",outcome="failed"}` increments.
- Consumer logs report a message routed to the dead-letter queue after 3 retries.
- `GET /orders/{id}` returns `404` for the affected order.

## Metrics

| Metric | Expected | Problematic |
|---|---|---|
| `events_dlq_total{topic="order-placed-dlq"}` | 0 or stable | increasing |
| `events_consumed_total{outcome="failed"}` | 0 or stable | increasing |

## Log Queries

```bash
# DLQ routing logs
cat order-query.log | jq 'select(.message | contains("dead-letter"))'
cat order-query.log | jq 'select(.message | contains("DLQ"))'
```

Trace fields to look for: `traceId`, `correlationId`, `service`.

## Triage

1. Inspect the dead-letter topic:
   ```bash
   docker compose exec kafka kafka-console-consumer.sh \
     --bootstrap-server kafka:9092 \
     --topic order-placed-dlq \
     --from-earliest
   ```
2. Identify the malformed payload, schema mismatch, or invalid domain value.
3. Note the `correlation-id` and `traceId` from the record headers to trace the originating request.

## Recovery

1. Stop or pause the source producing bad events if necessary.
2. Fix the root cause (schema, producer code, or upstream data).
3. Choose one:
   - Publish a corrected `OrderPlaced` event to `order-placed`.
   - Issue a compensating transaction if the bad event already committed in the write model.
4. Monitor `events_dlq_total` and `events_consumed_total` to confirm the consumer continues processing valid events.
