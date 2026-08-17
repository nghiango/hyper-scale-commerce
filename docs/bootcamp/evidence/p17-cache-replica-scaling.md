# Phase 17 Evidence Dossier: Cache and Read-Replica Scaling

- **Status:** PASSED
- **Date:** 2026-08-17T09:34:57Z
- **Topology:** six-node kind cluster; 3 app pods; 3 Order Query pods; Redis StatefulSet; 3-node Patroni PostgreSQL; 3-broker Kafka
- **Workload:** 5000 peak VUs, 2105.5808382796085 HTTP requests/second, read-heavy mixed traffic

| Metric | Target | Measured | Result |
|---|---:|---:|---|
| Catalog p95 | < 10 ms | 1.7681753499999981 ms | PASS |
| Order Query p95 outside injected fallback | < 20 ms | 6.216089249999996 ms | PASS |
| Order creation p95 | < 200 ms | 10.427374999999996 ms | PASS |
| Peak concurrency | >= 5,000 VUs | 5000 VUs | PASS |
| Failed requests during Redis deletion and replica replay pause | 0 | 0 | PASS |
| Primary PostgreSQL peak CPU outside forced fallback | < 15% of one-core limit | 14.514% | PASS |
| Observed fenced replica lag | > 100 ms | 1.106 s | PASS |
| Cross-schema reconciliation | 100% | 5655/5655 orders | PASS |

## Faults and Recovery

- Redis pod `redis-0` was deleted during steady load and the StatefulSet restored it.
- WAL replay was paused on both standbys, application lag gauges exceeded the
  100 ms routing fence, and replay was resumed after 10s.
- Order Query p95 during the intentionally degraded fault window was
  6.7105666999999745 ms; the normal read-offload SLO is reported separately.
- Primary CPU peaked at 27.855% during the intentional lag-fence
  fallback window; this fault-window value is disclosed separately from the
  normal read-offload acceptance measurement.
- No HTTP request failed during the combined qualification.

## Raw Evidence

- `build/qualification-results/cache/k6-cache-summary.json`
- `build/qualification-results/cache/primary-cpu-percent.txt`
- `build/qualification-results/cache/primary-cpu-baseline-percent.txt`
- `build/qualification-results/cache/primary-cpu-fault-percent.txt`
- `build/qualification-results/cache/replica-lag-seconds.txt`
- `build/qualification-results/cache/faults.log`
- `build/qualification-results/cache/reconciliation.md`
