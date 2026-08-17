# ADR-0018: API Idempotency Keys and Distributed Request Deduplication Strategy

## Status

Accepted

## Context

Under high-concurrency mobile and web traffic, network timeouts or connection resets can occur after a server commits a `POST /orders` request but before the client receives the HTTP 201 response. 

If clients retry without deduplication controls, duplicate orders, double charging, and redundant inventory deductions will occur.

---

## Decision

We adopt an **API Idempotency Key Standard** for all state-mutating REST APIs:

1. **Client-Supplied Header:**
   - Clients provide an `Idempotency-Key: <UUID>` header on state-changing requests (`POST /orders`).
2. **Atomic Key Reservation:**
   - The server inspects the `"order".idempotency_keys` table.
   - If the key is new: insert with status `IN_PROGRESS` and execute the order transaction.
   - If the key is `IN_PROGRESS` (concurrent request with same key): return `HTTP 409 Conflict`.
   - If the key is `COMPLETED`: return the exact cached response body and HTTP status code (`201 Created`) without re-executing business logic.
3. **Expiration Window:**
   - Idempotency keys are retained for 24 hours and pruned via scheduled TTL maintenance.

---

## Consequences

### Positive
- Completely eliminates duplicate order creation under network glitches and client retries.
- Safe client-side retry policies can be implemented with zero business risk.

### Negative / Tradeoffs
- Requires an additional database lookup / write during the order placement pipeline.
- Requires storage maintenance to prune expired keys.
