# Service Level Objectives

This document defines the SLOs for the HyperScale Commerce platform. The SLOs are observable from the existing `/actuator/prometheus` endpoints without any additional runtime infrastructure.

## Scraping

| Service | Prometheus endpoint |
|---|---|
| `app` | `http://localhost:8080/actuator/prometheus` |
| `order-query` | `http://localhost:8081/actuator/prometheus` |

## SLOs

| SLO | Target | Window | Source metric | PromQL |
|---|---|---|---|---|
| `GET /orders/{id}` p95 | < 200 ms | 1 minute | `http_server_requests_seconds{method="GET",uri="/orders/{id}",outcome="SUCCESS",quantile="0.95"}` | `slo_get_order_by_id_p95{slo="p95"}` |
| `GET /orders` p95 | < 300 ms | 1 minute | `http_server_requests_seconds{method="GET",uri="/orders",outcome="SUCCESS",quantile="0.95"}` | `slo_get_orders_p95{slo="p95"}` |
| `POST /orders` success rate | >= 99% | 1 minute | `http_server_requests_seconds_count{method="POST",uri="/orders"}` | `slo_post_orders_success_rate{slo="success.rate"}` or `rate(http_server_requests_seconds_count{method="POST",uri="/orders",outcome="SUCCESS"}[1m]) / rate(http_server_requests_seconds_count{method="POST",uri="/orders"}[1m])` |

## Notes

- The p95 percentiles are published by a `MeterFilter` that configures `http.server.requests` with `percentiles(0.95)`.
- All `http.server.requests` meters are tagged with `method`, `uri`, `outcome`, `status`, and `exception`.
- The `slo_*` gauges are derived from the same `http.server.requests` distribution to provide recording-rule-style convenience series.
