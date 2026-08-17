# Runbook: HAProxy Ingress & Multi-Replica Service Operations

## Overview

In Phase 14, incoming public HTTP traffic is mediated by HAProxy (ports 8080 and 8081) and balanced across stateless replica pools of `app` (`app-1`, `app-2`) and `order-query` (`order-query-1`, `order-query-2`).

---

## 1. Architecture & Port Mapping

| Port | Service Pool | Backends | Readiness Health Check |
|---|---|---|---|
| `8080` | `app` (Command/Catalog) | `app-1:8080`, `app-2:8080` | `GET /actuator/health/readiness` (2s interval, fall 2, rise 2) |
| `8081` | `order-query` (Query) | `order-query-1:8081`, `order-query-2:8081` | `GET /actuator/health/readiness` (2s interval, fall 2, rise 2) |
| `8404` | HAProxy Stats | Internal / admin stats | `GET /stats` |

---

## 2. Ingress Security & Sanitization Rules

1. **Header Sanitization:**
   - Client-provided `X-Forwarded-For` is deleted and replaced with `%[src]` (true connection IP).
   - `X-Forwarded-Proto: http` and `X-Forwarded-Port` are stamped.
2. **Access Control:**
   - Administrative and DLQ replay paths (`/admin/dlq`, `/orders/admin/dlq`) return `403 Forbidden` on public ingress ports.
   - Sensitive actuator endpoints (`/actuator/env`, `/actuator/beans`, etc.) return `404 Not Found`.
3. **Instance Tracing:**
   - Replicas return `X-Instance-Id: <replica-name>` in HTTP responses and write `instanceId` to MDC logs.

---

## 3. Operational Procedures

### Viewing Ingress Status & Stats
```bash
curl -s http://localhost:8404/stats
```

### Checking Backend Health
```bash
# App pool readiness check through HAProxy
curl -i http://localhost:8080/actuator/health/readiness

# Order-query pool readiness check through HAProxy
curl -i http://localhost:8081/actuator/health/readiness
```

### Performing a Zero-Downtime Rolling Restart
1. Gracefully stop `app-1`:
   ```bash
   docker stop hyperscale-app-1
   ```
   *HAProxy marks `app-1` down after 4 seconds ($2 \times 2\text{s}$ failed checks). Spring Boot drains active requests during the 30s shutdown phase. All traffic routes to `app-2`.*
2. Start the updated `app-1`:
   ```bash
   docker start hyperscale-app-1
   ```
   *HAProxy re-admits `app-1` after 2 consecutive successful checks ($4\text{s}$).*
3. Repeat the procedure for `app-2`.

---

## 4. Diagnostics & Troubleshooting

| Symptom | Probable Cause | Action |
|---|---|---|
| HTTP 503 Service Unavailable | All backends in the pool failed health checks | Inspect backend logs (`docker logs hyperscale-app-1`, `hyperscale-app-2`); verify PostgreSQL and Kafka connectivity. |
| Requests consistently hitting only one backend | Health check failing on other backend | Check `/actuator/health/readiness` on the missing backend directly. |
| HTTP 403 Forbidden on `/admin/*` | Expected behavior; public ingress blocks administrative paths | Execute admin/replay operations directly on the internal network or via authenticated admin harness. |
