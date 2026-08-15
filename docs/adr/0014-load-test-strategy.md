# ADR-0014: Load-Test Strategy and Qualification Model

- Status: Accepted
- Date: 2026-08-15
- Phase: 8 — Load Engineering

## Context

`docs/constitution.md` defines the engineering evolution of HyperScale Commerce toward a platform capable of handling:
- 10,000+ concurrent users,
- sub-200ms p95 latency for defined critical APIs,
- 5x traffic spikes with bounded degradation and graceful recovery,
- 99.9% availability (request success rate under load), and
- zero intentional data loss or duplicate business outcomes.

Following Phase 7 (ADR-0013: Observability Strategy), the platform operates as two deployables (`app` on port 8080 and `order-query` on port 8081) communicating asynchronously via Kafka (`order-placed`) with a shared PostgreSQL instance partitioned into service-owned schemas (`catalog`, `order`, `inventory`, and `order_query`).

Phase 2 performance tests utilized an in-process Kotlin JDK `HttpClient` harness embedded inside a Spring Boot integration test running against a single local JVM. That harness was valuable for early Catalog indexing and SQL tuning, but it:
1. Ran inside a test JVM rather than exercising external container networking, real TCP connection lifecycles, and HTTP proxy boundaries.
2. Predated Kafka, transactional outbox relays, asynchronous projections, service extraction, resilience mechanisms, and distributed tracing.
3. Conflated worker thread concurrency with request arrival rates.
4. Executed only 5-second bursts insufficient to surface connection pool exhaustion, JVM JIT warm-up, GC pauses, outbox backlog accumulation, or consumer lag.

Phase 8 requires establishing an external, black-box load-engineering capability to measure the unchanged Phase 7 system, identify the first true saturation bottlenecks, guide evidence-backed tuning, and formally qualify the platform against constitution targets without adding forbidden technologies (such as Kubernetes, distributed load clusters, Redis, or API gateways).

## Alternatives Considered

1. **k6 via pinned container image (chosen).**
   An open-source, scriptable load generator built in Go with a JavaScript scripting runtime. It natively supports both closed-system virtual-user (VU) models with explicit think time and open-system arrival-rate executors (`ramping-arrival-rate`, `constant-arrival-rate`). It runs efficiently as a single lightweight container, exports fine-grained metric summaries in machine-readable JSON/CSV, supports rich threshold assertions, and requires zero runtime or build dependencies in the Java/Kotlin application deployables.

2. **wrk / wrk2.**
   Extremely fast, low-overhead HTTP benchmarking tools written in C. Rejected: `wrk` only supports open/constant connection loops without multi-step workflow logic (e.g., executing `POST /orders`, capturing the generated order ID, and querying `GET /orders/{id}`); `wrk2` adds constant throughput but lacks scenario composition, structured JSON reporting, and programmatic threshold validation.

3. **Gatling.**
   High-performance Scala/Java/Kotlin-based load generation framework. Rejected: Gatling runs on the JVM, consuming substantial memory and CPU that would directly compete with the local System Under Test (SUT) on developer and qualification hosts. It also introduces JVM test harness complexity compared to a self-contained container runner.

4. **Apache JMeter.**
   Established enterprise load-testing tool. Rejected: heavy resource footprint, XML-based test definition files that are difficult to review and maintain in git, complex headless CLI execution, and unnecessary overhead for modern API load engineering.

5. **Expanding the in-process Kotlin HttpClient test harness.**
   Continuing to write Spring Boot integration tests that fire parallel HTTP requests. Rejected for capacity qualification: in-JVM tests do not exercise Docker bridge networking, real socket pool lifecycles, or container resource limits; they distort CPU and heap measurements by sharing the JVM or competing directly on the host JVM process. Retained exclusively for fast CI regression tests, not for capacity qualification.

6. **Distributed load generation infrastructure (e.g., distributed k6 operator or multi-node clusters).**
   Rejected: Distributed load infrastructure is explicitly deferred by Phase 8 rules. A single, resource-monitored k6 container is capable of generating tens of thousands of requests per second when properly sized, which is sufficient for Phase 8 qualification. Adding distributed generators would introduce unnecessary infrastructure complexity prematurely.

## Decision

Adopt **k6** executed as a test-only, pinned container image as the platform load generator and establish the following qualification model:

### 1. Test-Plane Topology and Container Isolation
- **Black-box execution:** k6 runs externally to the application containers, driving the real HTTP entry points (`http://app:8080` and `http://order-query:8081`) over the Docker network or host port mappings.
- **Pinned image:** Use the fixed official image:
  `grafana/k6:0.57.0@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b`
  Floating tags such as `latest` or unpinned version tags are strictly forbidden.
- **Zero runtime contamination:** No k6 libraries, agents, or test endpoints are added to `app`, `order-query`, or `contracts`.
- **Compose profile separation:** The k6 runner is defined under a dedicated test profile/overlay (`performance/compose.load.yml` or load profile) and is never started during normal application execution (`make up` or `make services`).
- **Target allow-listing:** The harness is hard-coded to allow only local Compose targets (`localhost`, `127.0.0.1`, `app`, `order-query`). Any execution against remote or non-local environments requires explicit authorization and environment parameter overrides.

### 2. Workload Models
- **Closed Concurrency Model (10,000 Concurrent Virtual Users):**
  - Models realistic user behavior with a fixed population of 10,000 concurrently active Virtual Users (VUs).
  - Each VU iterates through a realistic user journey with explicit, randomized think-time distributions (e.g., 1–3 seconds between actions).
  - Steady-state window lasts at least 15 minutes following a recorded warm-up phase.
- **Open Arrival-Rate Model (5x Traffic Spike):**
  - Uses k6 `ramping-arrival-rate` executor to decouple request arrival rate from system response latency.
  - Baseline rate ($1\times$) is derived from the sustainable steady-state throughput determined in baseline testing.
  - Offered rate ramps to $5\times$ baseline for a sustained burst of $\ge 60$ seconds, then returns to $1\times$ to observe recovery for at least 5 minutes.
- **Representative Traffic Mix:**
  - Catalog Browse & Detail (`GET /catalog/products*`): 80% of traffic.
  - Order Command (`POST /orders`): 10% of traffic.
  - Order Query (`GET /orders*`): 10% of traffic.
  - Asynchronous projection visibility check: Polling `GET /orders/{id}` with bounded retry to measure end-to-end event propagation latency.

### 3. Mapping Constitution Targets to Concrete Scenarios & Metrics

| Constitution Target | Scenario Profile | Primary Metric & Assertion | Supporting Signals |
|---|---|---|---|
| **10,000+ concurrent users** | Closed VU Steady-State (10,000 VUs, $\ge 15$ min) | `vus` gauge $\ge 10,000$ active throughout steady state | CPU/RAM headroom, no dropped iterations |
| **sub-200ms p95 latency for critical APIs** | Steady-State & Baseline | k6 `http_req_duration{endpoint:<name>}` p95 $< 200\text{ms}$ | Micrometer `http_server_requests_seconds` p95 |
| **5x traffic spike handling** | Open Arrival-Rate Spike ($1\times \to 5\times \to 1\times$) | Offered rate reaches $5\times$; latency/lag recovers to $1\times$ band within 5 min | Peak p95/p99, queue depth, outbox depth |
| **99.9% availability (request success)** | Steady-State & Spike Windows | `http_req_failed` rate $< 0.1\%$ ($\ge 99.9\%$ success) across all endpoints | HTTP 5xx error rate = 0, no readiness drop |
| **Zero intentional data loss / duplicate effects** | End-of-Run Async Drain | Post-test reconciliation script: accepted `POST /orders` = outbox events = Inventory reservations = Order query rows | DLQ counter = 0, consumer lag = 0, outbox depth = 0 |

### 4. Defined Critical APIs for Phase 8
The sub-200ms p95 threshold applies strictly to the 5 defined critical APIs:
1. `app`: `GET /catalog/products/{id}` (Product detail)
2. `app`: `GET /catalog/products?page=0&size=20` (Catalog browsing)
3. `app`: `POST /orders` (Order creation command)
4. `order-query`: `GET /orders/{id}` (Order detail read model)
5. `order-query`: `GET /orders?page=0&size=20` (Order history read model)

*(Note: Catalog substring search `GET /catalog/products?search=...` remains measured as a diagnostic reference but is excluded from the critical sub-200ms SLO due to its documented sequential scan behavior; dedicated search indexing is deferred to future architecture phases).*

### 5. Environment Tiers and Qualification Rules
- **Local Regression Runs:**
  - Fast smoke tests (`make load-smoke`, $< 30\text{s}$) and short stepped baselines run locally to validate script syntax, data seeding, and detect immediate regressions.
  - Used for rapid developer feedback and CI verification.
- **Formal Qualification Runs:**
  - Executed on a dedicated, documented qualification environment.
  - Full environment specifications must be recorded: host CPU model, physical core count, RAM allocation, OS kernel, Docker version, JVM heap (`-Xms`/`-Xmx`), garbage collector configuration, and initial dataset cardinality.
  - **Clean worktree requirement:** Runs executed with uncommitted changes or a dirty git status are marked non-qualifying regression evidence.
  - **Repetition rule:** Final 10,000-VU and 5x-spike qualifications require at least **three consecutive qualifying runs**; reported metrics must include the median and worst-case results across all three runs.
- **Generator Validity Checks (Anti-Distortion Safeguard):**
  - A load test is declared **INVALID** and discarded if the k6 generator itself experiences CPU saturation ($>90\%$), out-of-memory errors, dropped iterations (in arrival-rate scenarios), or connection pool exhaustion.
  - SUT and load-generator container resource utilization (`docker stats`) must be recorded alongside k6 metrics.

### 6. Workflow Integration and Evidence Storage
- **Separation from `make verify`:** Routine verification (`make verify`) MUST NOT execute long-running load scenarios. Routine builds run unit, integration, and architecture tests.
- **Dedicated Make targets:** Provide explicit targets: `make load-smoke`, `make load-baseline`, `make load-verify`, and `make load-spike`.
- **Raw Result Retention:** Ephemeral test artifacts and raw JSON metric outputs are written to `build/performance-results/` (which is git-ignored).
- **Committed Evidence:** Official baseline, tuning, and qualification reports are summarized and committed under `docs/bootcamp/evidence/` with links to raw summaries and commit SHAs.

## Operational Cost

- Maintaining JavaScript k6 test scripts, scenario definitions, and data generation fixtures in `performance/`.
- Adding Docker Compose configuration for the k6 runner.
- Managing database seed cardinality and test data resets between runs.
- Time investment required to execute multi-minute stepped baseline and 15-minute qualification runs during phase milestones.

## Failure Modes and Mitigations

- **Generator Saturation:** k6 runs out of host CPU or file descriptors, creating artificial latency.
  *Mitigation:* Preflight OS `ulimit -n` checks; sample generator CPU/memory during runs; enforce dropped-iteration checks in k6 thresholds.
- **Database Connection Pool Starvation:** 10,000 VUs exhaust HikariCP pools (default 20 connections).
  *Mitigation:* Measure Hikari connection wait time and pool usage via Actuator; tune pool sizes and timeouts only with evidence in P8-05.
- **Kafka & Outbox Relay Lag Accumulation:** Burst write traffic overwhelms the single-partition Kafka topic or 1-second outbox poller.
  *Mitigation:* Capture outbox depth, oldest uncommitted event age, and Kafka consumer group lag as primary time-series metrics.
- **Eventual-Consistency Polling 404s Counted as Failures:** Querying `GET /orders/{id}` immediately after `POST /orders` returns 404 before the projection completes.
  *Mitigation:* Tag polling attempts with custom k6 tags and handle expected initial 404s within a bounded polling loop; assert eventual consistency within a 2-second budget without polluting HTTP error rate metrics.
- **Unbounded Test Data Growth:** Generating tens of thousands of orders exhausts local disk space.
  *Mitigation:* Use dedicated ephemeral volumes or explicit test reset scripts before and after qualification runs.

## Consequences

- **P8-02** will define the detailed workload contracts, reconcile the Order query SLO document to p95 < 200ms, and update architecture documentation.
- **P8-03** will implement the k6 scripts, Docker Compose load profile, Make targets, and deterministic test data generation.
- **P8-04** will execute the untuned baseline against the unchanged Phase 7 system to identify saturation points.
- **P8-05** will apply evidence-guided tuning to relieve identified bottlenecks.
- **P8-06** and **P8-07** will execute the formal 10,000-VU and 5x spike qualification runs.
- Product application code and runtime architecture remain strictly unchanged by this ADR.
