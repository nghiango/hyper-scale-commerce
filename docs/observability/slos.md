# Service Level Objectives (SLOs)

This document defines the authoritative Service Level Objectives for the HyperScale Commerce platform. The SLOs are observable from the existing `/actuator/prometheus` endpoints without any additional runtime infrastructure.

## Scraping Endpoints

| Service | Prometheus Endpoint |
|---|---|
| `app` | `http://localhost:8080/actuator/prometheus` |
| `order-query` | `http://localhost:8081/actuator/prometheus` |

## Authoritative Critical API SLOs

In accordance with `docs/constitution.md` and the approved Phase 8 Load Engineering Plan (reconciling previous draft targets), the sub-200ms p95 latency and availability targets apply across all 5 defined critical APIs:

| Critical API | Deployable | SLO Target | Measurement Window | Source Metric / Filter | PromQL / Gauge |
|---|---|---|---|---|---|
| `GET /catalog/products/{id}` | `app` | p95 < 200 ms | 1 minute | `http_server_requests_seconds{method="GET",uri="/catalog/products/{id}",outcome="SUCCESS",quantile="0.95"}` | `http_server_requests_seconds{method="GET",uri="/catalog/products/{id}",quantile="0.95"}` |
| `GET /catalog/products?page=0&size=20` | `app` | p95 < 200 ms | 1 minute | `http_server_requests_seconds{method="GET",uri="/catalog/products",outcome="SUCCESS",quantile="0.95"}` | `http_server_requests_seconds{method="GET",uri="/catalog/products",quantile="0.95"}` |
| `POST /orders` | `app` | p95 < 200 ms | 1 minute | `http_server_requests_seconds{method="POST",uri="/orders",outcome="SUCCESS",quantile="0.95"}` | `http_server_requests_seconds{method="POST",uri="/orders",quantile="0.95"}` |
| `GET /orders/{id}` | `order-query` | p95 < 200 ms | 1 minute | `http_server_requests_seconds{method="GET",uri="/orders/{id}",outcome="SUCCESS",quantile="0.95"}` | `slo_get_order_by_id_p95{slo="p95"}` |
| `GET /orders?page=0&size=20` | `order-query` | p95 < 200 ms | 1 minute | `http_server_requests_seconds{method="GET",uri="/orders",outcome="SUCCESS",quantile="0.95"}` | `slo_get_orders_p95{slo="p95"}` |
| `POST /orders` success rate | `app` | >= 99.9% | 1 minute | `http_server_requests_seconds_count{method="POST",uri="/orders"}` | `slo_post_orders_success_rate{slo="success.rate"}` or `rate(http_server_requests_seconds_count{method="POST",uri="/orders",outcome="SUCCESS"}[1m]) / rate(http_server_requests_seconds_count{method="POST",uri="/orders"}[1m])` |
| End-to-end Request Success | Platform | >= 99.9% | Measured window | k6 `http_req_failed` rate < 0.1% | `sum(rate(http_server_requests_seconds_count{status=~"2.."}[1m])) / sum(rate(http_server_requests_seconds_count[1m]))` |
| Async Projection Visibility | Platform | p95 <= 2.0 s | Steady state | Measured end-to-end polling duration from `POST /orders` to 200 OK on `GET /orders/{id}` | `order_query_projection_lag_seconds` |

## SLO Reconciliation Record

- **Order List Query (`GET /orders`) Reconciliation:** Earlier Phase 7 documentation listed `GET /orders` p95 as `< 300 ms`. In Phase 8, this value has been reconciled to **`< 200 ms`** to align with the overarching platform Constitution target ("sub-200ms p95 latency for defined critical APIs") and the approved Phase 7/8 plans.
- **Success Rate Reconciliation:** Steady-state availability target is formalized at **$\ge 99.9\%$** (error rate $< 0.1\%$) for steady-state load qualification.
- **Non-Critical Diagnostic Endpoint:** `GET /catalog/products?query=...` (substring search) remains monitored as a diagnostic baseline but is explicitly excluded from the critical sub-200ms SLO due to its documented sequential scan behavior; dedicated search infrastructure is deferred to future architecture phases.

## Implementation Notes

- Percentile publishing is configured via `MeterFilter` beans in `app` and `order-query` that register `percentiles(0.95)` on `http.server.requests`.
- All `http.server.requests` meters are tagged with `method`, `uri`, `outcome`, `status`, and `exception`.
- Custom `slo_*` gauges in each deployable derive convenience series for rapid Prometheus and alert rule queries.
- Client-side end-to-end latency measured by the external k6 load generator is the authoritative qualification metric; in-process Micrometer percentiles provide supporting internal diagnostic confirmation.
