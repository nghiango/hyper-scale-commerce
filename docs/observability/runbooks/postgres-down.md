# PostgreSQL Down

## Symptoms

- Actuator health reports `db: DOWN` on affected services.
- `POST /orders` on `app` returns `5xx` error responses.
- `GET /orders/{id}` and `GET /orders` on `order-query` return `5xx`.
- HikariCP logs report connection acquisition timeouts.

## Metrics

| Metric | Expected | Problematic |
|---|---|---|
| `/actuator/health` `db` | `UP` | `DOWN` |
| `http_server_requests_seconds_count{method="POST",uri="/orders",outcome="SERVER_ERROR"}` | 0 | increasing |
| `order_read_model_lag_seconds` | low | may spike once recovery begins |

## Log Queries

```bash
# Hikari connection errors
cat app.log | jq 'select(.message | contains("Hikari"))'
cat order-query.log | jq 'select(.message | contains("Hikari"))'
```

Trace fields to look for: `traceId`, `correlationId`, `service`.

## Triage

1. Verify the PostgreSQL container/process is running:
   ```bash
   docker compose ps postgres
   ```
2. Check the persistent volume (`/var/lib/postgresql/data`) is intact.
3. Confirm both services report `db: DOWN` in actuator health.

## Recovery

1. Start or restart the PostgreSQL instance/container with the persistent volume mounted.
2. Wait for PostgreSQL to finish crash recovery and accept connections.
3. HikariCP will validate and recreate connections; actuator health `db: UP` will return.
4. On `app`, the transactional outbox relay resumes polling `order.outbox_events` and publishes any events committed before the outage.
5. On `order-query`, the projection consumer resumes committing to `order_query.order_read_model`.
6. Verify zero data loss by checking pre-outage orders exist in both write and read models.
