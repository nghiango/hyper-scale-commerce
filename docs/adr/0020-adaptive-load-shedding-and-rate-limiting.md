# ADR-0020: Adaptive Load Shedding, Rate Limiting, and Overload Protection

## Status

Accepted

## Context

During unexpected traffic surges ($>5\times$ spikes or DDoS attacks), incoming request volume can exceed the capacity of the thread pool or database connection pools. 

Unbounded request queuing leads to catastrophic latency degradation, resource exhaustion, and whole-process failures. We require an active overload protection mechanism to protect critical revenue-generating workflows.

---

## Decision

We adopt a **Priority-Based Adaptive Load Shedding Architecture**:

1. **Adaptive Concurrency Limiting (Little's Law Sensing):**
   - Continuously monitor 90th percentile response latency. If latency exceeds the 200ms SLO baseline, dynamically throttle the maximum admitted in-flight concurrency using an Additive Increase / Multiplicative Decrease (AIMD) algorithm.
2. **Priority-Tiered Shedding:**
   - When shedding load under severe saturation:
     - **Tier 1 (Critical - Protected):** `POST /orders`, Checkout transactions are never dropped.
     - **Tier 2 (Core):** `GET /orders/{id}`, Order lookup.
     - **Tier 3 (Degradable):** `GET /catalog/products`, Product search and recommendations are shed first with `HTTP 429 Too Many Requests` or `HTTP 503 Service Unavailable` with `Retry-After` headers.
3. **Ingress Token Bucket Rate Limiting:**
   - Apply per-client IP / API key rate limits at ingress to prevent individual rogue clients from monopolizing server capacity.

---

## Consequences

### Positive
- Prevents cascading JVM failures and out-of-memory crashes under extreme overloads.
- Protects revenue-critical checkout transactions during flash sales.

### Negative / Tradeoffs
- Degradable requests will receive 429/503 during extreme overload periods.
- Requires tuning of latency thresholds and concurrency limits.
