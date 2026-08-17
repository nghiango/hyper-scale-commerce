# Rate-Limiting Strategy & Ingress Quota Ownership

## Overview

In Phase 14 (ADR-0023, P14-05), the rate-limiting architecture establishes a clear division of responsibility between the edge ingress layer and the backend application runtime:

```text
                  Client Request (IP: 198.51.100.25)
                                  |
                                  v
                   +-----------------------------+
                   |       HAProxy Ingress       |
                   |  (Stick-Table: 500 req/min) |
                   +--------------+--------------+
                                  |
               [Overwrites X-Forwarded-For: 198.51.100.25]
                                  |
                   +--------------+--------------+
                   |                             |
                   v                             v
           +---------------+             +---------------+
           |     app-1     |             |     app-2     |
           | (Local Filter)|             | (Local Filter)|
           +---------------+             +---------------+
```

---

## 1. Division of Responsibilities

| Responsibility | Layer | Mechanism | Scope |
|---|---|---|---|
| **Topology-Wide Quota Enforcement** | HAProxy Ingress (`haproxy:2.9`) | Stick-Tables (`type ip size 100k expire 1m store http_req_rate(1m)`) | Cluster-global across all `app` and `order-query` backends |
| **Defense-in-Depth Bulkhead** | Application Runtime (`app`, `order-query`) | `ClientRateLimitFilter` (Caffeine cache window) | Process-local protection against internal/unproxied traffic |
| **Forwarding Header Sanitization** | HAProxy Ingress | `http-request del-header X-Forwarded-For` + `http-request set-header X-Forwarded-For %[src]` | Prevents client-side IP spoofing and quota bypass |

---

## 2. HTTP Status & Header Contract

When a client exceeds their rate limit:
- **HTTP Status:** `429 Too Many Requests`
- **Response Headers:**
  - `Retry-After: 60` (indicates retry backoff in seconds)
  - `Content-Type: application/json`
- **Response Body:**
  ```json
  {
    "error": "Too Many Requests",
    "message": "Rate limit exceeded. Please retry later."
  }
  ```

---

## 3. Security & Operational Boundaries

1. **Spoofing Immunity:** Clients cannot supply arbitrary `X-Forwarded-For` headers to masquerade as other clients or reset their quota; HAProxy unconditionally deletes incoming `X-Forwarded-For` headers and replaces them with the verified connection source IP (`%[src]`).
2. **Cardinality & Secret Safety:** Prometheus metrics (`http_rate_limited_requests_total`) increment counters without including dynamic client IPs or API keys as metric labels.
3. **Backend Churn Resilience:** Stopping, killing, or scaling application backend replicas (`app-1`, `app-2`) does not alter the ingress stick-table; client quota tracking remains continuous through service failovers.
4. **Documented Ingress Ephemerality:** Because HAProxy runs as a single container in the local qualification profile, restarting HAProxy itself clears stick-table memory. Multi-ingress synchronization (e.g. `peers` protocol or distributed cache) is deferred to future cloud deployment phases.
