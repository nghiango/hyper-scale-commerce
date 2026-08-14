# Phase 02 — Performance Engineering

Status: **APPROVED** — ready for implementation.

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-01.md` (implied, if present)
- `docs/bootcamp/phase-01-plan.md`
- `docs/adr/0001-technology-stack.md`
- `docs/adr/0002-catalog-bounded-context.md`
- The existing Phase 1 implementation (build, source, tests, CI)

---

## 1. Phase objective

Establish a measurable performance baseline for the Catalog API, identify the
first real bottlenecks, and tune the existing single-application stack to meet
Phase 2 latency and throughput targets without introducing new runtime
infrastructure. This phase proves that the platform can set, measure, and
progress toward the constitution's final performance targets.

---

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. Modular Monolith
3. **Performance Engineering** ← this phase
4. Event-Driven Architecture
5. ...

Phase 1 proved that a bounded context can live inside the modular monolith with
clean package boundaries, a read-only REST API, and architecture enforcement.
Phase 2 must now make those boundaries measurable and tune the simplest,
cheapest knobs before later phases introduce distributed systems. The goal is
not to hit the final 10,000-user, 200ms p95 target yet, but to set up the
process, the SLOs, and the tooling that will be used in every later phase.

---

## 3. Starting architecture / state

| Item | State |
|---|---|
| Application | Single Kotlin/Spring Boot `app` module with the Catalog bounded context |
| Root package | `com.hyperscale.commerce` with `modules.catalog` package layers |
| Database | PostgreSQL 16 via Docker Compose; `V2__catalog_product.sql` applied |
| Data access | `JdbcTemplate` / `NamedParameterJdbcTemplate` in `catalog.infrastructure` |
| API | `GET /catalog/products`, `GET /catalog/products/{id}`, `GET /catalog/products/sku/{sku}`, `GET /catalog/products/{id}/availability` |
| Metrics | Spring Actuator + Micrometer + Prometheus at `/actuator/prometheus` |
| Tests | JUnit 5, AssertJ, Testcontainers, ArchUnit, spotless, detekt |
| CI | GitHub Actions running `./gradlew build` |
| Docs | ADR-0001 (stack), ADR-0002 (catalog boundaries), README catalog notes |

Phase 1's Definition of Done is assumed complete: `make test` and `make verify`
pass from a clean checkout.

---

## 4. Target architecture / state

```text
                     Client
                       |
                       v
                   REST API
                       |
       +---------------+-----+-----+ ... (future)
       |                     |
   Catalog                 Customer
       |                   (future)
       v
  PostgreSQL
```

The single `app` module and the Catalog package structure from Phase 1 remain
unchanged. No new bounded contexts are added. The changes are strictly
operational and measurement-focused:

```text
app/
  src/
    integrationTest/kotlin/.../performance/   # reproducible load tests
    main/resources/application.yml            # tuned datasource + server
    main/resources/db/migration/V3__...       # optional, indexes only
  docs/bootcamp/evidence/                    # performance evidence
```

---

## 5. Problems this phase addresses

- The platform has no documented latency or throughput SLOs.
- There is no reproducible way to measure catalog endpoint performance.
- Connection pool, JVM, and HTTP server settings are all defaults.
- The `catalog.products` search query uses `ILIKE` on `name` and `sku` with
  `OFFSET` pagination; its behavior at higher data volumes is unmeasured.
- The application has not been exercised under load or under a 5x traffic
  spike.
- No evidence exists to justify later technologies (Redis, Kafka, etc.).

---

## 6. Architecture changes

- No new bounded contexts.
- No new data stores.
- No change to the package-dependency rules from Phase 1.
- Add a `performance` test package under `integrationTest` or a dedicated
  `performanceTest` source set to hold load-test harnesses.
- Update `application.yml` with tunable datasource and server settings.
- Optionally add one or more PostgreSQL indexes if the measured baseline
  justifies it.

---

## 7. Technology changes

- **No new runtime infrastructure.** PostgreSQL remains the only data store.
- Continue using `spring-boot-starter-jdbc` and `JdbcTemplate`.
- Continue using Micrometer / Prometheus for metrics.
- **Test tooling only:** a Kotlin-based load harness using JDK `HttpClient` (or
  an optional local `wrk`/`k6` Docker container for exploratory runs). No
  build-time dependency on external tools is required.
- No Redis, no Kafka, no Kubernetes, no Elasticsearch, no CQRS, no event
  sourcing, no service mesh, no read replicas, no microservices.

If the measured baseline justifies a non-default PostgreSQL extension (e.g.
`pg_trgm` for trigram indexes), that requires an ADR and approval before it is
used in a migration.

---

## 8. Non-functional requirements

- All existing `make test` and `make verify` checks continue to pass.
- All new performance tests are reproducible from a clean checkout.
- Every performance claim is backed by a load-test report with concurrency,
  throughput, and latency numbers.
- All new Kotlin code passes `spotlessCheck` and `detekt`.
- The application must still start with `make up` + `SPRING_PROFILES_ACTIVE=local`.
- No forbidden technology is introduced into the build or runtime.

---

## 9. Performance expectations

Phase 2 does **not** claim the final `< 200ms p95` target. Phase 2 targets on a
local Docker PostgreSQL instance with a 1,000-product catalog:

- `GET /catalog/products/{id}` p95 under **100ms** at 100 concurrent requests
  per second for 60 seconds.
- `GET /catalog/products` (default `size=20`, no query) p95 under **200ms** at
  100 concurrent requests per second for 60 seconds.
- `GET /catalog/products?query=...&page=0&size=20` p95 under **300ms** at 50
  concurrent requests per second for 60 seconds.
- The application can absorb a **5x traffic spike** (from 100 to 500 RPS on the
  read-by-id endpoint) for a 30-second burst without errors and without p95
  exceeding **300ms**.
- Throughput for `GET /catalog/products/{id}` must reach at least **500 RPS**
  on the local stack.
- Metrics for all catalog endpoints remain visible at `/actuator/prometheus`.

No formal multi-node or distributed load testing is required in this phase.

---

## 10. Reliability expectations

- The application starts only when `readinessState` and `db` health indicators
  are `UP`.
- Flyway migrations remain idempotent.
- Catalog endpoints remain available while PostgreSQL is healthy.
- All SQL queries remain parameterized.
- The phase must not degrade any Phase 1 health, metric, or OpenAPI endpoint.

---

## 11. Observability requirements

- Existing structured JSON logging continues for non-local profiles.
- Existing `/actuator/health`, `/actuator/prometheus`, and `/v3/api-docs`
  endpoints continue to work.
- Catalog REST operations are automatically timed and counted by Micrometer.
- The load harness must record and report p50, p95, p99, throughput, and error
  rate.
- Baseline and tuned numbers are captured in `docs/bootcamp/evidence/`.

---

## 12. Security considerations

- Catalog endpoints remain public and read-only; no authentication in this phase.
- Load test harness must not introduce open ports, credentials, or secrets.
- Logs must not include full stack traces for `4xx` client errors.
- SKU and search strings remain opaque text.

---

## 13. Data considerations

- Catalog continues to own the `catalog` PostgreSQL schema and `catalog.products`
  table.
- Performance tests may add a test-only seed data set; seed data belongs in a
  test source set or a repeatable Flyway migration, not in production.
- No other bounded context may read or write catalog tables directly.

---

## 14. Explicitly out-of-scope capabilities

- New bounded contexts (Customer, Cart, Order, Inventory, Payment, Shipping,
  Notification).
- New runtime technology (Redis, Kafka, Elasticsearch, etc.).
- Application containerization beyond the existing Compose PostgreSQL service.
- Multi-instance or multi-node deployment.
- Read replicas, materialized views, or CQRS.
- Full-text search, autocomplete, or search indexing beyond the existing
  `ILIKE` substring match.
- Caching (Redis, in-memory shared cache, etc.).
- Asynchronous messaging, events, sagas, or outbox patterns.
- Service extraction or separate deployables.
- Final constitution targets (10,000 concurrent users, 99.9% availability).

---

## 15. Dependencies on Phase 1

Phase 2 depends on the successful completion of Phase 1, specifically:

- P1-01 through P1-10 are complete and verified.
- `make test` and `make verify` pass from a clean checkout.
- The Catalog API, Flyway `V2__catalog_product.sql`, and ArchUnit tests are in
  place and functional.
- `docs/bootcamp/current-phase.md` has been advanced to Phase 01.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Local Docker results do not predict production behavior** | Medium | Treat all Phase 2 numbers as relative baselines; document environment and assumptions. |
| **The `ILIKE` search query is inherently slow at scale** | Medium | Cap catalog size for Phase 2 (1,000 products); document the need for a future search strategy. |
| **Tuning defaults over-fits the local environment** | Medium | Document every change and the measured impact; no production claims. |
| **Load test harness adds build time** | Low | Keep the harness in `integrationTest` or a separate `performanceTest` source set; do not run heavy tests on every unit-test invocation. |
| **Pressure to add Redis/Kafka prematurely** | Medium | Explicitly defer external stores in this plan; require ADR for any deviation. |

---

## 17. ADRs that may be required

- **ADR-0003 — Performance test harness and SLOs (optional).** Required only if
  the implementation team chooses an external load tool (e.g. `k6` or `wrk`) as
  a build dependency. If the harness remains a Kotlin test using JDK `HttpClient`,
  an ADR is not required.
- **ADR-0004 — PostgreSQL index or extension for catalog search (optional).**
  Required only if the measured baseline justifies a non-default PostgreSQL
  feature such as `pg_trgm` or a partial/composite index.
- **ADR-0005 — Connection pool and HTTP server tuning (optional).** Required only
  if the tuning changes are non-obvious or introduce values that must be
  defended by measurements.

---

## 18. Ordered implementation tasks

### P2-01 — Define SLOs and performance budget

- **Objective:** Document the latency and throughput targets for the catalog
  endpoints and define the load-test methodology before writing any test code.
- **Context:** Phase 2 cannot be verified without explicit, measurable targets.
- **Dependencies:** Phase 1 complete.
- **Scope:** Documentation only.
- **Implementation requirements:**
  - Define SLOs for the four catalog endpoints.
  - Choose the load-test harness strategy (Kotlin `HttpClient` inside the
    repository, or an optional external tool for manual runs).
  - Define the test data size, concurrency levels, duration, and success
    criteria.
  - Record decisions in this plan or in an ADR if an external tool is selected.
- **Acceptance criteria:**
  - SLOs and methodology are documented in `docs/bootcamp/phase-02-plan.md` or
    an ADR.
  - No forbidden technology is required by the chosen strategy.
- **Verification requirements:** Document review; no automated tests.
- **Expected files/components:**
  - `docs/bootcamp/phase-02-plan.md` (updated)
  - Optional `docs/adr/0003-performance-test-harness.md`
- **Architecture impact:** None.
- **Out of scope:** Writing the load-test harness or changing application code.

### P2-02 — Add catalog seed data for performance testing

- **Objective:** Provide a reproducible data set so that load tests run against
  a realistic catalog size.
- **Context:** Performance numbers are meaningless on an empty table.
- **Dependencies:** P2-01.
- **Scope:** Test-only data and a small amount of supporting code.
- **Implementation requirements:**
  - Add a repeatable Flyway migration or a test-only seed helper that creates
    1,000 products in the `catalog.products` table.
  - Seed data must not be required in production.
  - SKUs and names must be varied enough to exercise the search endpoint.
- **Acceptance criteria:**
  - `make up` + `make run` with a `local` profile and the seed migration loaded
    results in 1,000 catalog products.
  - A `CatalogSeedDataIntegrationTest` or `CatalogPerformanceSetupTest` confirms
    the count.
- **Verification requirements:** `make verify` passes; seed count test passes.
- **Expected files/components:**
  - `app/src/main/resources/db/migration/V3__catalog_seed_performance.sql` or a
    test-only seed class
  - `app/src/integrationTest/kotlin/.../catalog/...PerformanceSetupTest.kt`
- **Architecture impact:** None; test-only data.
- **Out of scope:** Production seed data, admin endpoints.

### P2-03 — Add the load-test harness and establish a baseline

- **Objective:** Create a reproducible load test that exercises the catalog
  endpoints and produces the first performance numbers.
- **Context:** The harness is the only way to verify Phase 2 SLOs.
- **Dependencies:** P2-02.
- **Scope:** Kotlin load-test code and documentation.
- **Implementation requirements:**
  - Implement a `CatalogLoadTest` using JDK `HttpClient` in the `integrationTest`
    or `performanceTest` source set.
  - The harness must support concurrency, duration, and ramp-up.
  - Report p50, p95, p99, throughput (RPS), and error rate.
  - Save the raw baseline report under `docs/bootcamp/evidence/`.
- **Acceptance criteria:**
  - `make verify` still passes.
  - The baseline run produces a report for each catalog endpoint.
  - The baseline numbers are committed as markdown under
    `docs/bootcamp/evidence/p2-baseline.md`.
- **Verification requirements:** Run the harness and review the report.
- **Expected files/components:**
  - `app/src/integrationTest/kotlin/.../performance/CatalogLoadTest.kt`
  - `docs/bootcamp/evidence/p2-baseline.md`
- **Architecture impact:** None; test-only code.
- **Out of scope:** Optimization, tuning.

### P2-04 — Profile catalog endpoints and identify bottlenecks

- **Objective:** Analyze the baseline to determine whether latency is in the
  JVM, the HTTP server, the database, the SQL query, or JSON serialization.
- **Context:** Optimization must be evidence-driven, not speculative.
- **Dependencies:** P2-03.
- **Scope:** Profiling, query plan review, and documentation.
- **Implementation requirements:**
  - Capture `EXPLAIN ANALYZE` for `findById`, `findBySku`, `search`, and `count`.
  - Capture Micrometer timings from `/actuator/prometheus` during the load test.
  - Capture JVM and Hikari pool metrics.
  - Document the top one or two bottlenecks.
- **Acceptance criteria:**
  - A `p2-profile.md` report exists in `docs/bootcamp/evidence/`.
  - The report names the bottleneck(s) and provides supporting data.
- **Verification requirements:** Manual review of `EXPLAIN ANALYZE` and
  Prometheus metrics.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p2-profile.md`
- **Architecture impact:** None.
- **Out of scope:** Code changes, except for adding Micrometer tags if needed.

### P2-05 — Tune database connection pool and query performance

- **Objective:** Apply the cheapest database and connection-pool optimizations
  to reduce endpoint latency.
- **Context:** Hikari and PostgreSQL defaults are unlikely to be optimal for the
  Phase 2 SLOs.
- **Dependencies:** P2-04.
- **Scope:** `application.yml`, one optional Flyway index migration.
- **Implementation requirements:**
  - Tune `spring.datasource.hikari.*` properties.
  - Tune `server.tomcat.threads.*` properties if the HTTP layer is a bottleneck.
  - If the measured baseline justifies it, add a PostgreSQL index via a Flyway
    migration (document the `EXPLAIN` difference).
  - Do not add any new runtime technology.
- **Acceptance criteria:**
  - `make verify` passes.
  - The tuned settings are in `application.yml` or a Flyway migration.
  - A `p2-tuning.md` report captures each change and its measured impact.
- **Verification requirements:** Re-run `CatalogLoadTest` and compare numbers.
- **Expected files/components:**
  - `app/src/main/resources/application.yml`
  - Optional `app/src/main/resources/db/migration/V4__catalog_indexes.sql`
  - `docs/bootcamp/evidence/p2-tuning.md`
- **Architecture impact:** Operational; no package-dependency change.
- **Out of scope:** External caches, read replicas, PostgreSQL extensions.

### P2-06 — Tune HTTP and JVM runtime behavior

- **Objective:** Apply the cheapest application-side optimizations for the
  catalog endpoints.
- **Context:** Compression, thread pool, and logging levels can materially
  affect latency.
- **Dependencies:** P2-05.
- **Scope:** `application.yml` and `logback-spring.xml` if needed.
- **Implementation requirements:**
  - Evaluate and, if justified, enable response compression for JSON.
  - Evaluate and, if justified, tune Tomcat acceptor and max threads.
  - Ensure non-local profiles emit JSON logs at `INFO` without debug noise that
    adds latency under load.
  - Capture the measured impact of each change.
- **Acceptance criteria:**
  - `make verify` passes.
  - A `p2-runtime-tuning.md` report exists.
  - The tuning is defended by a before/after measurement.
- **Verification requirements:** Re-run `CatalogLoadTest` and compare numbers.
- **Expected files/components:**
  - `app/src/main/resources/application.yml`
  - `docs/bootcamp/evidence/p2-runtime-tuning.md`
- **Architecture impact:** Operational; no package-dependency change.
- **Out of scope:** JVM GC changes, native images, containerization.

### P2-07 — Verify Phase 2 performance SLOs

- **Objective:** Run the final load tests and confirm that the catalog endpoints
  meet the Phase 2 SLOs, including the 5x spike test.
- **Context:** This is the primary verification gate for the phase.
- **Dependencies:** P2-06.
- **Scope:** Load-test execution and evidence capture.
- **Implementation requirements:**
  - Run the `CatalogLoadTest` for each SLO scenario.
  - Run the 5x spike test for `GET /catalog/products/{id}`.
  - Save the final report under `docs/bootcamp/evidence/p2-slo-verification.md`.
- **Acceptance criteria:**
  - `GET /catalog/products/{id}` p95 <= 100ms at 100 RPS.
  - `GET /catalog/products` p95 <= 200ms at 100 RPS.
  - `GET /catalog/products?query=...` p95 <= 300ms at 50 RPS.
  - 5x spike (100 RPS → 500 RPS for 30s) on `/{id}` has zero errors and p95 <=
    300ms.
- **Verification requirements:** Run `CatalogLoadTest`; review the SLO report.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p2-slo-verification.md`
- **Architecture impact:** None.
- **Out of scope:** Production deployment, multi-node tests.

### P2-08 — Phase 2 final verification

- **Objective:** Confirm the entire phase is complete and ready for phase
  review.
- **Context:** Last task before phase review.
- **Dependencies:** P2-07.
- **Scope:** Run all gates and gather evidence.
- **Implementation requirements:**
  - Run `make clean && make verify` from a fresh checkout.
  - Review git diff for unrelated changes.
  - Confirm all performance evidence is committed under
    `docs/bootcamp/evidence/`.
  - Update `docs/bootcamp/current-phase.md` **only if** phase review has
    already passed (do not change the phase status before review).
- **Acceptance criteria:**
  - `make verify` passes with all new and existing tests.
  - No unrelated files modified.
  - Phase exit criteria are met.
- **Verification requirements:** Command output, git status, manual checklist.
- **Expected files/components:** None new.
- **Architecture impact:** None.
- **Out of scope:** Phase 2 implementation beyond verification; advancing to
  Phase 3.

### Dependency graph

```text
P2-01 ──> P2-02 ──> P2-03 ──> P2-04 ──> P2-05 ──> P2-06 ──> P2-07 ──> P2-08
```

### Suggested execution order

P2-01 → P2-02 → P2-03 → P2-04 → P2-05 → P2-06 → P2-07 → P2-08

---

## 19. Phase exit criteria

Phase 2 is complete only when all of the following are true:

1. All tasks P2-01 through P2-08 are implemented and verified.
2. `make verify` passes from a clean checkout with no manual intervention beyond
   JDK 21, Docker, and Make.
3. The CI pipeline is green for the changes.
4. The Catalog API meets the documented Phase 2 SLOs:
   - `GET /catalog/products/{id}` p95 <= 100ms at 100 RPS.
   - `GET /catalog/products` p95 <= 200ms at 100 RPS.
   - Search endpoint p95 <= 300ms at 50 RPS.
   - 5x spike on `/{id}` succeeds with p95 <= 300ms and zero errors.
5. All performance claims are backed by evidence files under
   `docs/bootcamp/evidence/`.
6. No forbidden technology (Kafka, Redis, Kubernetes, microservices, CQRS,
   Elasticsearch, event sourcing) has been introduced.
7. Git diff is clean and no unrelated files are modified.
8. The phase review process has been passed before `current-phase.md` is
   updated to Phase 03.
