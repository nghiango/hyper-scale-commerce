# Phase 5 — Service Extraction Evidence

## P5-06 — Container images and Compose wiring

Environment: local Docker (Compose), PostgreSQL 16 + Kafka 7.7.1 containers,
JDK 21 JRE images (eclipse-temurin:21-jre), Spring Boot 4.0.

### Topology

`docker compose --profile services up -d --build` starts:

| Service | Image | Port | Health |
|---|---|---|---|
| `app` | hyperscale-commerce-app:0.0.1-SNAPSHOT | 8080 | healthy |
| `order-query` | hyperscale-commerce-order-query:0.0.1-SNAPSHOT | 8081 | healthy |

Both services `depends_on` healthy PostgreSQL and Kafka.

### End-to-end order flow across the two containers

```text
POST /orders on app (8080)
  → {"id":3,"status":"PLACED","items":[{"sku":"PERF-SKU-00002","quantity":1}],...}
GET /orders/3 from order-query (8081)
  → {"id":3,"status":"PLACED","items":[{"sku":"PERF-SKU-00002","quantity":1}],...}
GET /orders?page=0&size=20 from order-query (8081)
  → {"total":3,"items":[...]}
```

The event flows app → outbox → Kafka `order-placed` → `order-query` projection
→ `order_query.order_read_model` → query API.

### Metrics observed

- `app`: `events_published_total{topic="order-placed"} 2.0`
- `order-query`: `events_consumed_total{consumer="order-query",event_type="OrderPlaced",outcome="processed"} 2.0`, `order_read_model_lag_seconds` gauge present

### Wiring fixes discovered during verification

1. **Flyway history-table collision.** Both services shared the default
   `flyway_schema_history` table; `order-query`'s `V1` collided with the
   monolith's `V1__baseline.sql` (checksum mismatch). Fixed by scoping
   `order-query`'s Flyway to its own schema
   (`spring.flyway.schemas: order_query`), giving each service its own history
   table per ADR-0011.
2. **Kafka advertised listeners.** The broker advertised
   `PLAINTEXT://localhost:9092`, unreachable from inside the compose network,
   so the app producer and `order-query` consumer could not connect. Fixed with
   dual listeners: `PLAINTEXT://kafka:9092` (container-to-container) and
   `PLAINTEXT_HOST://localhost:9092` (host dev, mapped from container port
   29092).

### Verification

- `docker compose --profile services up` — both services healthy; order flow
  works end-to-end across the two containers.
- `make verify` — BUILD SUCCESSFUL.

## P5-08 — Evidence capture and phase gate

This section records the read-model rebuild procedure and the catalog SLO
re-verification that closes Phase 5.

### Read model rebuild procedure

The `order_query.order_read_model` is derived from the durable `order-placed`
topic and can be rebuilt by replaying the topic with a fresh or reset consumer
group.

Steps:

1. Stop the `order-query` service (or scale it to zero).
2. Truncate `order_query.order_read_model` so the projection starts empty.
3. Reset the `order-query` consumer group offsets for `order-placed` to the
   earliest position:

```bash
docker compose --profile services exec kafka \
  kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 \
    --group order-query \
    --topic order-placed \
    --reset-offsets \
    --to-earliest \
    --execute
```

4. Start `order-query`. The projection consumer replays all `order-placed`
   events from the beginning and re-populates `order_query.order_read_model`.
5. Verify with `GET /orders/{id}` for known orders and `GET /orders` for the
   full list.

Because the projection upserts on `order_id`, the replay is idempotent even if
some events are re-processed.

### Catalog SLO re-verification

Catalog SLOs from Phase 2 were re-exercised during `make verify` by
`CatalogSloVerificationTest` and did not regress. The full report is in
`docs/bootcamp/evidence/p2-slo-verification.md`.

### Conclusion

Phase 5 implementation is complete. The `order-query` service is an
independently deployable, event-driven query side; the monolith keeps the
command, catalog, and inventory paths. No forbidden technology (Kubernetes, API
gateway, service mesh, Redis, event sourcing) was introduced.

## P5-07 — Cross-service end-to-end test

Environment: one test JVM booting both applications against shared Testcontainers (PostgreSQL 16 + Kafka 7.7.1), JDK 21, Spring Boot 4.0.

### Startup-order case

`order-query` was started first and became healthy before the monolith started; the flow succeeded once both were up.

### Flow

`POST /orders` on the monolith → transactional outbox → Kafka `order-placed` → `order-query` projection → `order_query.order_read_model` → `GET /orders/{id}` from `order-query`

| Step | Detail |
|---|---|
| POST /orders | 201, order id 1 |
| Outbox event | OrderPlaced |
| Projection | read model row created for order 1 |
| GET /orders/1 | 200, served from `order_query.order_read_model` |

### Timings

| Measurement | Value |
|---|---:|
| POST /orders latency | 105.1 ms |
| End-to-end (POST → read model visible) | 1538.3 ms |

### Idempotency

The OrderPlaced event was replayed on the topic; the read model row count remained 1 and the row was unchanged.

### Query endpoint p95 under concurrent load

Load: 10 concurrent users, ramp-up 1s, duration 3s.

| Endpoint | p95 (ms) | Throughput (RPS) | Error rate |
|---|---:|---:|---:|
| GET /orders/{id} | 1.85 | 8484.67 | 0.00 |
| GET /orders | 2.17 | 7515.67 | 0.00 |
