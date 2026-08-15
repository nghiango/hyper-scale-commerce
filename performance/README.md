# External Load-Engineering Plane

This directory defines the external, black-box load testing and capacity qualification harness for HyperScale Commerce.

The harness evaluates the containerized two-service platform (`app` on port 8080 and `order-query` on port 8081) against the performance and resilience targets defined in `docs/constitution.md` and `docs/adr/0014-load-test-strategy.md`.

---

## 1. Load Plane Architecture

```text
                  external load plane (test only)
             +-----------------------------------+
             | k6 scenarios + result summaries  |
             | resource/metric snapshot scripts |
             +----------------+------------------+
                              |
                  +-----------+-----------+
                  |                       |
                  v                       v
             app :8080              order-query :8081
       Catalog + Order command        Order queries
                  |                       ^
                  v                       |
          order.outbox_events             |
                  |                       |
                  +----> Kafka -----------+
                           |
                     Inventory consumer
                  |
                  v
             PostgreSQL 16
```

### Core Principles
- **Black-Box Execution:** k6 operates outside the JVM processes and drives real HTTP ports (`http://app:8080`, `http://order-query:8081`).
- **Pinned Container Image:** k6 runs using the pinned official image:
  `grafana/k6:0.57.0@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b`
  Floating tags (such as `latest`) are strictly forbidden.
- **Zero Runtime Contamination:** No test libraries, mock controllers, or load-testing dependencies exist in `app` or `order-query`.
- **Target Allow-Listing:** Load scripts are strictly locked to local Compose targets (`http://app:8080`, `http://order-query:8081`, `http://localhost:8080`, `http://localhost:8081`). Non-local or remote execution requires explicit authorization.

---

## 2. Directory Structure

```text
performance/
├── README.md               # Architecture, qualification rules, environment capture
├── workloads.md            # Workload contracts, scenario definitions, thresholds
├── k6/                     # k6 scenario scripts and shared helper modules (P8-03)
│   ├── smoke.js
│   ├── baseline.js
│   ├── qualification-10k.js
│   ├── spike-5x.js
│   └── lib/
│       ├── config.js
│       ├── endpoints.js
│       ├── journeys.js
│       └── metrics.js
├── scripts/                # Test orchestration, metrics snapshots, reconciliation (P8-03)
│   ├── preflight.sh
│   ├── snapshot-metrics.sh
│   ├── reconcile-data.sh
│   └── run-scenario.sh
└── compose.load.yml        # Load runner Compose profile (test only)
```

Ephemeral test output is written to `build/performance-results/` (ignored by git). Committed evidence reports are stored in `docs/bootcamp/evidence/`.

---

## 3. Environment Tiers & Qualification Rules

### Local Regression Tier
- **Purpose:** Fast developer feedback, script syntax validation, and immediate regression detection.
- **Commands:** `make load-smoke` ($< 30\text{s}$), short baseline checks.
- **Evidence:** Used for exploratory diagnostics; does not qualify constitutional targets.

### Formal Qualification Tier
- **Purpose:** Official verification of 10,000 concurrent VUs, sub-200ms p95 latency, 5x spike recovery, 99.9% success rate, and zero data loss.
- **Commands:** `make load-verify`, `make load-spike`.
- **Requirements for a Qualifying Run:**
  1. **Clean Git Worktree:** The repository must be clean (`git status` shows no uncommitted changes). Runs on dirty worktrees are marked non-qualifying.
  2. **Environment Metadata Capture:** Host hardware, Docker resources, and JVM settings must be captured.
  3. **Three Consecutive Runs:** Final qualification requires at least **three consecutive qualifying repetitions**; the report must record median and worst-case metrics.
  4. **Generator Validity Confirmation:** Generator CPU and memory must remain below saturation limits.
  5. **Post-Test Reconciliation:** All accepted `POST /orders` commands must be verified against outbox events, Inventory reservations, and Order query read-model rows.

---

## 4. Generator Validity & Anti-Distortion Rules

A load test is declared **INVALID** and its results discarded if:
1. **Generator CPU Saturation:** k6 container CPU utilization exceeds $90\%$ for more than 5 consecutive seconds.
2. **Generator Memory Starvation:** k6 exhausts allocated RAM or experiences container OOM kills.
3. **Dropped Iterations:** k6 arrival-rate executor reports dropped iterations (`dropped_iterations > 0`).
4. **Socket Exhaustion:** k6 reports client-side `dial tcp: connection refused` or socket starvation caused by host `ulimit` exhaustion rather than SUT rejection.

---

## 5. Qualification Environment Capture Template

Every formal qualification run must record the following metadata in its evidence report:

```markdown
### Environment Metadata
- **Timestamp (UTC):** `YYYY-MM-DDTHH:MM:SSZ`
- **Commit SHA:** `<git-rev-parse-HEAD>`
- **Git Worktree State:** `clean`
- **Host OS & Kernel:** `macOS / Linux <version>`
- **Host CPU:** `<Model, physical cores, logical cores>`
- **Host Memory:** `<Total RAM GB>`
- **Docker Version & Storage Driver:** `Docker <version>, overlay2`
- **Docker Resource Limits:** CPU: `<cores>`, Memory: `<GB>`
- **Application JVM Settings:**
  - `app`: Java 17, `-Xms<size> -Xmx<size>`, GC: `<G1GC/ZGC>`
  - `order-query`: Java 17, `-Xms<size> -Xmx<size>`, GC: `<G1GC/ZGC>`
- **Initial Data Cardinality:**
  - Catalog products: `10,000`
  - Seed inventory: `1,000,000` per SKU
  - Initial Orders: `0`
- **k6 Container Image:** `grafana/k6:0.57.0@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b`
```

---

## 6. Separation from Routine Verification

To preserve rapid feedback in daily development:
- `make verify` compiles, checks styles/detekt, and executes unit, integration, and architecture tests in $< 30\text{s}$.
- Long-running load tests are never invoked implicitly by `make verify` or `./gradlew build`.
- Load testing is triggered exclusively via dedicated Make targets (`make load-smoke`, `make load-baseline`, `make load-verify`, `make load-spike`).
