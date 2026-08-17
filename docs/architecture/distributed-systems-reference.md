# Advanced Distributed Systems Reference & Engineering Blueprint

**System:** HyperScale Commerce  
**Document:** Distributed Systems Reference, Failure Patterns, and Architectural Blueprints  
**Status:** **ACTIVE REFERENCE**  
**Author:** AI Distributed Systems Architect  
**Target Scale:** 10,000+ Concurrent Users, 5x Spikes, Sub-200ms p95 Latency, Zero Intentional Data Loss  

---

## Implementation Status

This document contains both implemented patterns and future architectural
blueprints. As of completed Phase 13:

| Capability | Status |
|---|---|
| Transactional outbox, idempotent consumers, CQRS, sagas, schema compatibility | Implemented and locally verified |
| Caffeine near-cache, `SKIP LOCKED` workers, load shedding | Implemented and locally verified |
| DLQ replay and out-of-order projection guards | Implemented and locally verified |
| Multi-replica application runtime and multi-broker Kafka | Planned for Phase 14 |
| PostgreSQL HA, partitioning/sharding, multi-region DR | Blueprint only; later phase |
| SPIFFE/mTLS and tail-sampling collector backend | Blueprint only; later phase |

The current client rate limiter is per `app` process. It must not be described
as a cluster-global distributed quota until an ingress or shared-state design
has been implemented and verified.

---

## Table of Contents

1. [Distributed Sagas & Compensating Transactions](#1-distributed-sagas--compensating-transactions)
2. [API Idempotency Keys & Request Deduplication](#2-api-idempotency-keys--request-deduplication)
3. [Event Schema Evolution & Compatibility Management](#3-event-schema-evolution--compatibility-management)
4. [Distributed Caching, Stampede Mitigation & Consistency](#4-distributed-caching-stampede-mitigation--consistency)
5. [Adaptive Load Shedding, Rate Limiting & Overload Protection](#5-adaptive-load-shedding-rate-limiting--overload-protection)
6. [Distributed Job Scheduling & Multi-Worker Coordination](#6-distributed-job-scheduling--multi-worker-coordination)
7. [Database Partitioning, Sharding & Zero-Downtime Migrations](#7-database-partitioning-sharding--zero-downtime-migrations)
8. [Multi-Region Replication, PACELC Tradeoffs & Disaster Recovery](#8-multi-region-replication-pacelc-tradeoffs--disaster-recovery)
9. [Zero-Trust Workload Security & Mutual TLS (mTLS)](#9-zero-trust-workload-security--mutual-tls-mtls)
10. [High-Throughput Distributed Tracing & Tail-Based Sampling](#10-high-throughput-distributed-tracing--tail-based-sampling)

---

## 1. Distributed Sagas & Compensating Transactions

### The Problem
In a microservices or modular distributed system, business operations span multiple bounded contexts with separate databases (e.g. Order placement in `"order"` schema $\to$ Inventory allocation in `"inventory"` schema $\to$ Payment capture $\to$ Shipping). Two-Phase Commit (2PC) is rejected because it introduces blocking distributed locks, degrades availability (violates CAP theorem), and collapses throughput under load.

### Architectural Solution: Choreographed / Orchestrated Saga
HyperScale Commerce adopts a **Choreographed Saga** driven by the Transactional Outbox and Kafka:

```text
[Client] ──POST /orders──> [Order Service] ──(DB Tx)──> [orders table + outbox_events]
                                                                  |
                                                           (Outbox Relay)
                                                                  v
                                                        [Kafka: order-placed]
                                                                  |
                                       +--------------------------+--------------------------+
                                       |                                                     |
                                       v                                                     v
                            [Inventory Service]                                     [Order Query Service]
                         (Check & Reserve Stock)                                  (Project Read Model)
                                       |
                   +-------------------+-------------------+
                   | (Success)                             | (Out of Stock / Failure)
                   v                                       v
         [Kafka: inventory-reserved]             [Kafka: inventory-reservation-failed]
                   |                                       |
                   v                                       v
          [Payment Service]                       [Order Service]
          (Capture Charge)                        (Compensate: Set Status CANCELLED)
                                                           |
                                                           v
                                                  [Kafka: order-cancelled]
```

### Key Engineering Rules:
1. **Forward Recovery (Retryable):** If a failure is transient (e.g. DB connection dropped, network glitch), consumers retry with exponential backoff and jitter.
2. **Backward Recovery (Compensating Transactions):** If a failure is non-retryable (e.g. item out of stock, payment declined), emit a compensating event (`InventoryReservationFailed`). Upstream services consume this event and execute semantic undo actions (`Order` transitions from `PLACED` to `CANCELLED`).
3. **Idempotency Requirement:** Every compensating transaction must be strictly idempotent. Replaying an `order-cancelled` event multiple times must yield the identical final state.

---

## 2. API Idempotency Keys & Request Deduplication

### The Problem
When a mobile client or frontend issues `POST /orders`, a network disconnect or client-side timeout can occur *after* the server committed the transaction but *before* the HTTP 201 response reached the client. If the client retries naively, duplicate orders, double billing, and over-allocation of inventory occur.

### Architectural Solution: Idempotency Key Pipeline
```text
[Client] ──POST /orders (Header: Idempotency-Key: uuid)──> [Idempotency Filter]
                                                                   |
                                          +------------------------+------------------------+
                                          | Key Not Found                                   | Key Exists
                                          v                                                 v
                              [Execute Order Transaction]                        [Inspect Saved Response]
                              [Atomically Save Key + Output]                                |
                                          |                                      +----------+----------+
                                          v                                      | IN_PROGRESS         | COMPLETED
                                    [Return 201]                                 v                     v
                                                                            [Return 409]          [Return Cached 201]
```

### PostgreSQL Idempotency Table Schema:
```sql
CREATE TABLE IF NOT EXISTS "order".idempotency_keys (
    key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL, -- IN_PROGRESS, COMPLETED, FAILED
    response_code INT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '24 hours')
);
CREATE INDEX idx_idempotency_expires ON "order".idempotency_keys (expires_at);
```

---

## 3. Event Schema Evolution & Compatibility Management

### The Problem
In production, domain events evolve: new fields are added, existing fields are deprecated, and data structures are modified. If an event producer deploys a breaking change, downstream consumers crash or fail to deserialize.

### Compatibility Guarantees & Migration Lifecycle:
1. **Backward Compatibility (Default):** New consumer can read events produced by older producers. (Consumers ignore unknown fields using Jackson `@JsonIgnoreProperties(ignoreUnknown = true)`).
2. **Forward Compatibility:** Older consumers can read events produced by newer producers.
3. **Full Compatibility:** Both backward and forward compatible.

### Schema Evolution Rules:
- **Rule 1 (Additive-Only):** Only add optional/nullable fields or fields with explicit defaults. Never remove or rename existing fields in an active version.
- **Rule 2 (Dual-Read / Dual-Write Deprecation Window):** When refactoring a field (e.g. `priceCents` to `priceAmount` + `currency`):
  - *Phase A:* Producer writes both old and new fields.
  - *Phase B:* Consumers are updated to read the new field with fallback to old.
  - *Phase C:* Producer deprecates old field after all consumers are upgraded.
- **Rule 3 (Explicit Schema Versioning):** Every event contract specifies `version: Int`. Breaking structural changes require incrementing the version and registering a dedicated topic or payload deserializer.

---

## 4. Distributed Caching, Stampede Mitigation & Consistency

### The Problem
High-volume read paths (`GET /catalog/products`, `GET /orders/{id}`) encounter high latency or database connection pool exhaustion if every request hits PostgreSQL directly at 50,000+ RPS. However, naive caching introduces **Cache Stampedes (Thundering Herd)** and stale data anomalies.

### Architectural Solution: Multi-Tier Cache Architecture
```text
[HTTP Request] ──> [L1: Local In-Memory Cache (Caffeine)] (TTL: 5s, micro-cache)
                          | (Cache Miss)
                          v
                   [L2: Distributed Cache (Redis / Memcached)] (TTL: 5m)
                          | (Cache Miss / Lock)
                          v
                   [PostgreSQL 16 Read Replica / Model]
```

### Mitigating Cache Stampedes:
1. **Probabilistic Early Expiration (XFetch Algorithm):** Recompute and refresh the cache in the background *before* it strictly expires based on request frequency:
   $$\Delta \beta \ln(\text{rand}()) > \text{expiry} - \text{now}$$
2. **Distributed Mutex (Singleflight Pattern):** Only one worker thread queries PostgreSQL on cache miss; concurrent threads wait for the single in-flight database query result.
3. **Cache Invalidation via Domain Events:** Instead of relying solely on TTLs, listen to `ProductUpdated` or `OrderPlaced` Kafka events to proactively invalidate/update cache keys (`CACHE:PRODUCT:<SKU>`).

---

## 5. Adaptive Load Shedding, Rate Limiting & Overload Protection

### The Problem
During catastrophic 10x traffic spikes or DDoS attacks, queueing incoming requests causes memory exhaustion, thread pool starvation, and runaway latency spikes that crash the JVM.

### Architectural Solution: Priority-Based Adaptive Load Shedding
```text
                       [Incoming Ingress Traffic]
                                  |
                                  v
                  [Adaptive Concurrency Limiter]
              (Measures p90 latency vs normal baseline)
                                  |
                  +---------------+---------------+
                  | Normal Latency                | Latency Spiking (> 200ms)
                  v                               v
         [Admit All Requests]             [Priority Filter]
                                                  |
                                  +---------------+---------------+
                                  | Critical Traffic              | Non-Critical Traffic
                                  | (POST /orders, Checkout)      | (Search, Recommendations)
                                  v                               v
                         [Process Request]                 [Drop: HTTP 429 / 503]
```

### SRE Formulas:
- **Little's Law:** $L = \lambda \times W$ (Concurrency Limit = Target Throughput $\times$ Target Latency).
- **Additive Increase / Multiplicative Decrease (AIMD):** Dynamically adjust max concurrent requests based on observed RTT (Round-Trip Time) degradation.

---

## 6. Distributed Job Scheduling & Multi-Worker Coordination

### The Problem
When `app` is scaled out to 10 container replicas (`app-1`, `app-2`, ..., `app-10`), every replica runs a background outbox relay thread. Without coordination, all 10 replicas contend for the exact same rows in `"order".outbox_events`, causing row-level lock contention, serialization deadlocks, and duplicated Kafka messages.

### Architectural Solution: Row-Level Non-Blocking Claims
HyperScale Commerce uses PostgreSQL's native `FOR UPDATE SKIP LOCKED`:

```sql
WITH claimable AS (
    SELECT id 
    FROM "order".outbox_events
    WHERE published_at IS NULL
    ORDER BY created_at ASC
    LIMIT 500
    FOR UPDATE SKIP LOCKED
)
UPDATE "order".outbox_events
SET published_at = now()
WHERE id IN (SELECT id FROM claimable)
RETURNING id, aggregate_type, aggregate_id, event_type, payload;
```
- **Zero Inter-Node Coordination Overhead:** No Redis locks or Zookeeper/Consul clusters required.
- **Lock-Free Concurrency:** Each replica claims its own distinct partition of unpublished events without blocking peer replicas.

---

## 7. Database Partitioning, Sharding & Zero-Downtime Migrations

### The Problem
As commerce volume grows to hundreds of millions of orders, single-table query performance degrades and table maintenance (`VACUUM`, `REINDEX`) locks resources.

### Architectural Blueprint:
1. **Range Partitioning by Date:**
   ```sql
   CREATE TABLE "order".orders (
       id BIGSERIAL,
       created_at TIMESTAMPTZ NOT NULL,
       status VARCHAR(32) NOT NULL,
       PRIMARY KEY (id, created_at)
   ) PARTITION BY RANGE (created_at);

   CREATE TABLE "order".orders_2026_q3 PARTITION OF "order".orders
       FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');
   ```
2. **Zero-Downtime Migrations (Expand / Contract Pattern):**
   - *Step 1 (Expand):* Add new column as nullable in Flyway migration (`V7__add_new_col.sql`).
   - *Step 2 (Deploy):* Deploy application writing to both old and new columns.
   - *Step 3 (Backfill):* Run asynchronous background batch job backfilling historical rows.
   - *Step 4 (Contract):* Add `NOT NULL` constraint and drop obsolete column in a subsequent release.

---

## 8. Multi-Region Replication, PACELC Tradeoffs & Disaster Recovery

### The Problem
Data center failures require maintaining high availability and zero data loss across geographic regions.

### PACELC Classification for HyperScale Commerce:
- **Under Network Partition ($P$):**
  - Order Writes $\to$ **$PC/EC$ (Consistency over Availability):** Orders require strict consistency to prevent double-spending inventory.
  - Catalog Browsing & Order Query $\to$ **$PA/EL$ (Availability & Latency over Consistency):** Read models serve reads from regional replicas with eventual consistency.

```text
[Primary Region: us-east-1]                             [Secondary Region: us-west-2]
  [PostgreSQL Primary (RW)] ──(Streaming Async Rep)───>   [PostgreSQL Replica (RO)]
  [Kafka Cluster 1]         ──(MirrorMaker 2)─────────>   [Kafka Cluster 2]
  [App (Active)]                                          [App (Standby / Read Only)]
```

- **RPO (Recovery Point Objective):** $< 1\text{s}$ (Async replication lag).
- **RTO (Recovery Time Objective):** $< 30\text{s}$ (Automated DNS failover + Promote Read Replica).

---

## 9. Zero-Trust Workload Security & Mutual TLS (mTLS)

### Security Architecture:
1. **Service Identity (SPIFFE/SPIRE):** Every container receives a cryptographically signed X.509 SVID (Service Identity) bound to its runtime identity.
2. **Mutual TLS Everywhere:**
   - Client $\leftrightarrow$ App: TLS 1.3 with strict cipher suites.
   - App $\leftrightarrow$ Kafka: SSL/TLS authentication with client certificates.
   - App $\leftrightarrow$ PostgreSQL: `sslmode=verify-full` with root CA certificate validation.
3. **Data Masking in Logs & MDC:**
   - Customer PII (Email, Phone, Cardholder details) masked in Logback formatters (`p**@example.com`).
   - Sensitive headers (`Authorization`, `Cookie`) stripped from OpenTelemetry span tags.

---

## 10. High-Throughput Distributed Tracing & Tail-Based Sampling

### The Problem
At 10,000+ RPS, capturing 100% of distributed trace spans generates terabytes of trace data per day, saturating network and storage backends.

### Architectural Solution: Tail-Based Sampling Collector
```text
[App / Order Query] ──(Stream 100% of Spans in Memory)──> [OpenTelemetry Collector]
                                                                  |
                                              +-------------------+-------------------+
                                              | Span Has Error (5xx / Exception)      | Span Successful (200 OK)
                                              v                                       v
                                   [100% Sample & Persist]                     [1% Probabilistic Sample]
                                              |                                       |
                                              +-------------------+-------------------+
                                                                  v
                                                        [Trace Storage (Jaeger)]
```
- **100% Capture of Failures:** Every single error, timeout, or DLQ routing is permanently recorded with full span context.
- **99% Cost Reduction for Happy Paths:** High-volume successful read requests are sampled at 1% for latency profiling, reducing storage overhead by 95%+.
