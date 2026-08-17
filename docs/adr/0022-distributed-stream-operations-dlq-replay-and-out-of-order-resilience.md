# ADR-0022: Distributed Stream Operations, DLQ Replay & Out-of-Order Event Resilience

## Status
Accepted

## Context
In distributed event-driven systems (specifically with Apache Kafka, consumer groups, transactional outboxes, and CQRS projections), operational edge cases occur when dealing with stream failures and network non-determinism:
1. **Dead Letter Queue (DLQ) Operational Recovery:** When poison messages or transient database unavailabilities cause events to exhaust bounded retries, they are routed to dead letter topics (`order-placed-dlq`, `order-cancelled-dlq`). Without an operational replay mechanism, resolving the root cause leaves DLQ events stranded, requiring manual SQL scripts that risk data inconsistency.
2. **Out-of-Order Event Ingestion & Monotonic Versioning:** During Kafka partition rebalancing, network packet reordering, or concurrent producer retries, events for a single aggregate might arrive out of order (e.g. `OrderCancelled` arriving at `order-query` before `OrderPlaced`). Projections must maintain monotonic sequence/version checks to prevent older state transitions from overwriting newer committed state.
3. **Per-Instance Fixed-Window Rate Limiting:** Protecting critical edge services against abusive clients and noisy neighbors requires per-client/IP rate limiting with standard HTTP `429 Too Many Requests` and `Retry-After` headers. Phase 13 has one `app` instance, so this decision does not define a cluster-global quota.

## Decision
1. **Administrative DLQ Inspection and Controlled Replay Engine:**
   - Provide an operational DLQ management API / administrative service with search, inspect, and replay capabilities.
   - Replayed messages are published back to the original topic with re-drive headers (`X-Redrive-Count`, `X-Original-DLQ-Topic`) while preserving original event causation and correlation IDs.
   - Consumers leverage existing idempotency keys and projection deduplication to safely re-process replayed records.
2. **Monotonic Aggregate Versioning in CQRS Read-Model Projections:**
   - Add monotonic `version` tracking to domain aggregates and events.
   - Update `ORDER_READ_MODEL` with aggregate `version` column.
   - Projection consumers execute optimistic sequence validation: reject or ignore events whose version is $\le$ current stored version, ensuring out-of-order deliveries never corrupt state.
3. **In-Memory Fixed-Window Rate Limiting Filter:**
   - Implement an IP/client-based fixed-window rate limiter filter in the application web layer with configurable rate limits (e.g. 500 requests/minute per client).
   - Emit HTTP 429 and `Retry-After` headers when limits are exceeded, and register `http_rate_limited_total` Prometheus metrics.
   - Treat counters as process-local. A later multi-replica phase must define
     ingress or shared-state ownership before describing the quota as
     distributed or topology-wide.

## Consequences
### Positive
- Operational self-healing: dead-lettered events can be repaired and re-injected without manual DB patching.
- Strong eventual consistency: out-of-order event streams cannot overwrite later state transitions.
- Client protection: API endpoints are shielded against misbehaving clients and denial-of-service attempts.

### Negative / Tradeoffs
- Requires administrative endpoints with strict access control.
- Projections require version tracking in schemas and events.
- Rate-limit counters reset with the process and are not shared across replicas.

## Failure Modes & Mitigations
- **Infinite Replay Loops:** Replayed messages carry `X-Redrive-Count`. Records at the maximum redrive count are skipped by the replay operation and remain in the source DLQ for operator action.
- **Out-of-Order Version Gaps:** If an event arrives with a future version gap ($version > current + 1$), it is processed idempotently if self-contained or buffered.
