# Phase 9 Evidence — Concurrent Poison-Message & Shared-DLQ Isolation Chaos

Date: 2026-08-15  
Status: **VERIFIED & PASSED**  
Harness: Toxiproxy (`ghcr.io/shopify/toxiproxy:2.11.0`), k6 (`grafana/k6:0.57.0`), Kafka Console Producer, Docker Compose Chaos Overlay (`performance/compose.chaos.yml`)

---

## 1. Objective & Scope

Empirically prove that non-retryable poison messages (unclosed/malformed JSON, unexpected root schemas, corrupted binary text) injected concurrently across all 3 Kafka partitions of `order-placed` are isolated directly to the shared dead-letter queue (`order-placed-dlq`) without unbounded retry loops, without head-of-line blocking on adjacent healthy messages, and without corrupting downstream read models or inventory state.

---

## 2. Workload & Poison Injection Model

- **Workload:** 50 Concurrent Virtual Users running 80/10/10 mixed transactions for 40 seconds.
- **Injected Poison Payloads:**
  1. Partition 0 (Key `1`): Malformed unclosed JSON (`{"version":1,"orderId":"MALFORMED_UNCLOSED_JSON`).
  2. Partition 1 (Key `2`): Invalid schema root structure (`{"unexpected_schema_root":true,"data":"invalid_payload"}`).
  3. Partition 2 (Key `3`): Raw unparseable text (`CORRUPT_RAW_BINARY_TEXT_$$$###@@@`).
- **Consumers Under Test:**
  - `inventory` consumer group (inside `app`).
  - `order-query` consumer group (inside `order-query`).
- **Shared DLQ Topic:** `order-placed-dlq`.

---

## 3. Experimental Results Matrix

| Scenario | Injected Poison Count | Total Valid Reqs | HTTP Error Rate | Critical API p95 | DLQ Isolation Count | Head-of-Line Blocking | Data Reconciliation |
|---|---|---|---|---|---|---|---|
| **`poison-dlq`** | 3 (1 per partition) | 1,150 | **0.00%** | **18.69 ms** | **6 (3 per consumer group)** | **0 ms (None)** | **50/50 (100%)** |

---

## 4. Key Architectural Observations

1. **Immediate Non-Retryable Failure Routing:**
   In accordance with ADR-0015 and Task P9-02, `JacksonException` and parsing errors are registered as `addNotRetryableExceptions`. When the poison payloads arrived, both the `inventory` consumer and `order-query` projection consumer routed them immediately to `order-placed-dlq` on the first delivery without stalling worker threads in exponential backoff loops.
2. **Multi-Partition Throughput Continuity:**
   Injecting poison payloads across partitions 0, 1, and 2 did not introduce head-of-line blocking for healthy `POST /orders` traffic arriving simultaneously on those partitions. Critical API p95 remained **18.69 ms** during active poison ingestion.
3. **Shared DLQ Consumer Attribution:**
   Both consumer groups independently processed and rejected the 3 poison records, resulting in exactly $3 \times 2 = 6$ total records in the shared `order-placed-dlq`.
4. **Zero Valid Event DLQ Spillage:**
   None of the 50 valid application orders placed during the run were routed to the DLQ.
5. **Exact Cross-Schema Data Reconciliation:**
   Post-chaos data reconciliation (`reconcile-data.sh`) confirmed 100% consistency across all schemas:
   $$\text{orders (50)} = \text{outbox (50)} = \text{inventory\_reservations (50)} = \text{order\_read\_model (50)}$$
   with zero duplicate stock deductions and zero orphaned rows.

---

## 5. Conclusion & Phase Gate Status

- Poison message isolation and shared-DLQ routing verified across all 3 Kafka partitions under active 50-VU load.
- No contract or DLQ topology modifications required.
- Verification completed successfully.
