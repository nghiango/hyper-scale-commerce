# P18-07 — Invalidated In-Process Smoke Result

> **Status: NON-QUALIFYING / INVALIDATED 2026-08-17.** The failed Phase 18
> review established that this result came from a single-process integration
> smoke test, not the required Kubernetes qualification. It is retained only
> as an audit record and must not be used as Phase 18 performance evidence.

## 1. Executive Summary

This report preserves measurements from the removed
`Phase18QualificationSuiteTest`. That test used 50 workers for three seconds,
200 workers for two seconds, and 50 workers for three seconds against one
random-port `app` instance and one PostgreSQL Testcontainer.

It did not exercise 10,000 VUs, a 5x spike, a 15-minute steady state, three
consecutive runs, public Kubernetes ingress, both services, JFR, isolated
faults, asynchronous drain, or data reconciliation. Its throughput calculation
also excluded ramp-up time from the denominator. The numbers below are
therefore historical smoke output only.

---

## 2. Test Results & Metrics

| Benchmark Workload | Total Requests | Success Rate | Measured Throughput | p95 Latency | p99 Latency | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Steady-State Baseline** | 60495 | 100.0% | 20165.0 RPS | 5.23 ms | 19.92 ms | **INVALIDATED** |
| **5x Traffic Spike Burst** | 34862 | 100.0% | 17431.0 RPS | 38.65 ms | 59.69 ms | **INVALIDATED** |
| **Soak Memory Stability** | 49143 | 100.0% | Sustained | Initial Heap: 325 MB | Post-Soak Heap: 318 MB | **INVALIDATED** |

---

## 3. What remains valid

Focused unit and integration tests cover replica fencing, Kafka-outage outbox
recovery, and bounded DLQ behavior, but they are not substitutes for the
required multi-pod fault run. The short heap samples do not prove leak
resistance or GC behavior. No resilience, throughput, latency, availability,
or memory conclusion in the old result qualifies Phase 18.
