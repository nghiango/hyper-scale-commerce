# Phase 00 — Implementation Plan

Status: PROPOSED — not yet approved, not yet implemented.

This plan was produced by inspecting the repository and the documents:

- AGENTS.md
- docs/constitution.md
- docs/requirements.md
- docs/architecture.md
- docs/bootcamp/phase-00.md

No code has been written.

---

# 1. Current State

The repository contains documentation only.

| Item | State |
|---|---|
| `AGENTS.md` | Exists. Engineering rules and phase constraints. |
| `docs/constitution.md` | Exists. Mission, evolution stages, domain boundaries, ADR rules. |
| `docs/requirements.md` | Exists. Business domain requirements (Catalog, Customer, Cart, Order, Inventory, Payment, Shipping, Notification). |
| `docs/architecture.md` | Exists. States current stage = Phase 0, target = modular monolith (REST API → modules → PostgreSQL). |
| `docs/bootcamp/phase-00.md` | Exists. Phase 0 goals, constraints, acceptance criteria, definition of done. |
| Git | Repository initialized on branch `main` with **zero commits**. |
| Source code | None. No build system, no application, no tests. |
| `.gitignore` | Missing. |
| Technology stack | **Not specified anywhere in the documentation.** Selected with the repository owner: **Java / Spring Boot**. |
| CI | None. No CI platform is specified in the documentation. |

## Technology stack decision

Selected stack (confirmed with repository owner, not derived from docs):

- Java 21 (LTS)
- Spring Boot 3.x (web, validation, actuator, micrometer-prometheus)
- Gradle with Kotlin DSL, Gradle wrapper committed
- Flyway (migrations) + PostgreSQL JDBC driver
- PostgreSQL 16 via Docker Compose
- JUnit 5 + AssertJ (unit), Testcontainers PostgreSQL (integration)
- springdoc-openapi (OpenAPI generation + Swagger UI)
- Spotless (google-java-format) for formatting verification
- Checkstyle for static analysis
- GitHub Actions for CI (assumed; see Risks)
- Make as the local task runner (`make test`, `make verify`)

---

# 2. Gaps

Everything in Phase 0 is currently missing. Mapping acceptance criteria → gaps:

| Phase 0 requirement | Current state | Gap |
|---|---|---|
| Repository builds from clean checkout | No build system | Build system + wrapper missing |
| Documented development setup | Not present | README with setup steps missing |
| Documented architecture / engineering rules | `architecture.md`, `AGENTS.md`, `constitution.md` | Present; architecture.md needs no change for Phase 0 |
| Application starts / config / graceful shutdown | No application | Application skeleton, config management, graceful shutdown missing |
| PostgreSQL via Docker Compose | Not present | `compose.yaml` missing |
| Migrations execute automatically | Not present | Flyway + baseline migration missing |
| App connects to PostgreSQL | Not present | Datasource config + connectivity verification missing |
| Unit tests configured | Not present | JUnit setup + sample test missing |
| Integration tests, isolated DB infrastructure | Not present | Testcontainers-based integration test setup missing |
| Health + readiness endpoints | Not present | Actuator health groups missing |
| Structured logging | Not present | JSON logback config missing |
| Application metrics | Not present | Micrometer Prometheus endpoint missing |
| OpenAPI generated / docs available | Not present | springdoc-openapi missing |
| CI pipeline (install → compile → unit → integration → static analysis → format) | Not present | CI workflow missing |
| `make test` / `make verify` from clean checkout | Not present | `Makefile` missing |

## Contradictions and ambiguities found

1. **No technology stack is specified** in any document. Resolved by owner
   decision (Java / Spring Boot). This decision must be recorded in an ADR
   because it is the foundational infrastructure choice.
2. **CI platform unspecified.** Assumed GitHub Actions (`.github/workflows`).
   If the project uses another CI system, task P0-13 changes.
3. **`make test` semantics ambiguous.** Phase 0 DoD says a clean checkout must
   run `make test` and `make verify` "without manual intervention beyond
   required local infrastructure". Interpretation: `make test` runs unit +
   integration tests (Docker required for Testcontainers); `make verify` runs
   the full gate: build, all tests, static analysis, formatting check.
4. **Migrations "execute automatically"** — interpreted as Flyway running on
   application startup (standard Spring Boot behavior), not a separate manual
   step.
5. **`architecture.md` diagram shows only Catalog, Cart, Order** while the
   constitution lists 8 bounded contexts. Phase 0 implements none of them;
   the skeleton must not pre-create domain modules (no business features
   allowed). The diagram is illustrative and needs no change in Phase 0.
6. **Application containerization is not required by Phase 0.** Only
   PostgreSQL runs in Docker Compose. The application runs locally via the
   Gradle wrapper. Adding an app image now would exceed "minimum
   implementation".

---

# 3. Proposed Implementation

Minimal implementation, in dependency order:

1. **Repository hygiene** — `.gitignore` (Java/Gradle/IDE), `README.md` with
   dev setup, `docs/adr/0001-technology-stack.md` recording the stack
   decision. Initial git commit.
2. **Gradle skeleton** — wrapper, `settings.gradle.kts`, `build.gradle.kts`
   (single module `app`), Spring Boot 3.x on Java 21, main application class
   with no business code.
3. **Configuration management** — `application.yml` with typed
   `@ConfigurationProperties`, environment-variable overrides, `local` and
   `test` profiles, graceful shutdown (`server.shutdown: graceful`).
4. **Docker Compose** — PostgreSQL 16 with healthcheck, named volume, fixed
   local credentials for dev only.
5. **Flyway** — dependency + baseline migration `V1__baseline.sql`
   (schema placeholder only, no business tables); runs automatically on
   startup; `flyway_schema_history` verifies execution.
6. **Health checks** — Spring Actuator with `liveness` and `readiness`
   probes exposed at `/actuator/health/liveness` and
   `/actuator/health/readiness`; readiness includes the database.
7. **Structured logging** — `logback-spring.xml` with JSON encoder
   (logstash-logback-encoder) in non-local profiles, console pattern locally;
   no secrets logged.
8. **Metrics** — Micrometer Prometheus registry, `/actuator/prometheus`.
9. **OpenAPI** — springdoc-openapi; spec at `/v3/api-docs`, UI at
   `/swagger-ui.html`.
10. **Unit tests** — JUnit 5 + AssertJ wired into Gradle `test` task with a
    context-free sample unit test.
11. **Integration tests** — separate `integrationTest` source set and Gradle
    task using Testcontainers PostgreSQL; verifies datasource connectivity,
    Flyway execution, health endpoints, OpenAPI endpoint.
12. **Static analysis + formatting** — Spotless (`spotlessCheck`) with
    google-java-format; Checkstyle wired into `check`.
13. **Makefile** — `make build`, `make up`, `make down`, `make test`,
    `make verify` (verify = format check + static analysis + build + all
    tests).
14. **CI pipeline** — GitHub Actions workflow: dependency install/cache →
    compile → unit tests → integration tests (Docker available on hosted
    runners) → static analysis → formatting verification.

Nothing else. No domain modules, no business endpoints, no additional
infrastructure.

---

# 4. Task Breakdown

## P0-01 — Repository hygiene and stack ADR

- **Objective:** Make the repository committable and record the stack
  decision.
- **Files/components:** `.gitignore`, `README.md`,
  `docs/adr/0001-technology-stack.md`, initial git commit.
- **Dependencies:** none.
- **Verification method:** Manual review; `git status` clean after commit;
  clone simulation shows no build artifacts.
- **Acceptance criteria:** `.gitignore` covers Gradle/Java/IDE artifacts;
  README documents prerequisites (JDK 21, Docker, Make) and setup steps;
  ADR-0001 records problem, alternatives, decision, operational cost.
- **Architecture impact:** None (documentation only; ADR records, does not
  change, architecture).

## P0-02 — Gradle + Spring Boot application skeleton

- **Objective:** Buildable, startable application with no business logic.
- **Files/components:** `settings.gradle.kts`, `build.gradle.kts`, Gradle
  wrapper files, `app/src/main/java/.../Application.java`,
  `app/src/main/resources/application.yml` (minimal).
- **Dependencies:** P0-01.
- **Verification method:** `./gradlew build` succeeds; application starts and
  exits with graceful shutdown on SIGTERM.
- **Acceptance criteria:** Clean-checkout build works using only the wrapper
  and JDK 21; no business code present.
- **Architecture impact:** Establishes the modular-monolith container
  (single deployable). Consistent with `docs/architecture.md`.

## P0-03 — Configuration management

- **Objective:** Externalized, typed, validated configuration.
- **Files/components:** `application.yml`, `application-local.yml`,
  `application-test.yml`, typed `@ConfigurationProperties` class with
  validation.
- **Dependencies:** P0-02.
- **Verification method:** Unit test that config binds from properties;
  startup failure on invalid/missing required values.
- **Acceptance criteria:** No secrets in source; environment overrides work;
  graceful shutdown enabled via config.
- **Architecture impact:** None.

## P0-04 — PostgreSQL via Docker Compose

- **Objective:** Local PostgreSQL with one command.
- **Files/components:** `compose.yaml` (PostgreSQL 16, healthcheck, named
  volume, dev-only credentials via environment defaults).
- **Dependencies:** none (parallel with P0-02/P0-03).
- **Verification method:** `docker compose up -d` then `docker compose ps`
  reports healthy; `pg_isready` succeeds.
- **Acceptance criteria:** Database reachable on documented port; data
  survives container restart via volume.
- **Architecture impact:** Confirms PostgreSQL as source of truth (already
  mandated by docs).

## P0-05 — Flyway migrations

- **Objective:** Automatic, versioned schema management.
- **Files/components:** Flyway dependencies, `V1__baseline.sql`,
  datasource + Flyway config in `application.yml`.
- **Dependencies:** P0-02, P0-03, P0-04.
- **Verification method:** Start app against Compose PostgreSQL; confirm
  `flyway_schema_history` contains V1; restart app and confirm idempotency.
- **Acceptance criteria:** Migrations run automatically at startup; app
  fails fast if migration fails; no business tables created.
- **Architecture impact:** None.

## P0-06 — Health and readiness endpoints

- **Objective:** Liveness and readiness probes.
- **Files/components:** Actuator dependency, health group config in
  `application.yml`.
- **Dependencies:** P0-02, P0-05 (readiness includes DB).
- **Verification method:** Integration test asserts
  `/actuator/health/liveness` = UP and `/actuator/health/readiness`
  reflects database state.
- **Acceptance criteria:** Readiness goes DOWN when the database is
  unreachable; liveness stays UP.
- **Architecture impact:** None.

## P0-07 — Structured logging

- **Objective:** JSON structured logs suitable for aggregation.
- **Files/components:** `logback-spring.xml`, logstash-logback-encoder
  dependency.
- **Dependencies:** P0-02.
- **Verification method:** Start app with non-local profile; assert log lines
  parse as JSON with timestamp, level, logger, message fields.
- **Acceptance criteria:** No secrets or sensitive data in log config;
  local profile keeps human-readable logs.
- **Architecture impact:** None.

## P0-08 — Metrics

- **Objective:** Application metrics endpoint.
- **Files/components:** micrometer-registry-prometheus dependency, actuator
  exposure config.
- **Dependencies:** P0-02.
- **Verification method:** Integration test asserts `/actuator/prometheus`
  returns Prometheus-format metrics including JVM and HTTP metrics.
- **Acceptance criteria:** Endpoint returns 200 with non-empty metric set.
- **Architecture impact:** None.

## P0-09 — OpenAPI generation

- **Objective:** Generated API specification and documentation UI.
- **Files/components:** springdoc-openapi dependency, OpenAPI metadata
  config bean.
- **Dependencies:** P0-02.
- **Verification method:** Integration test asserts `/v3/api-docs` returns a
  valid OpenAPI 3 document.
- **Acceptance criteria:** `/v3/api-docs` and `/swagger-ui.html` available
  in local profile; spec reflects actual endpoints (actuator excluded from
  business API groups).
- **Architecture impact:** None.

## P0-10 — Unit test setup

- **Objective:** Working unit test infrastructure.
- **Files/components:** JUnit 5 + AssertJ dependencies, Gradle `test` task
  config, one sample unit test (e.g., config binding test from P0-03).
- **Dependencies:** P0-02, P0-03.
- **Verification method:** `./gradlew test` runs and passes; test report
  generated.
- **Acceptance criteria:** Tests run without Docker or a database.
- **Architecture impact:** None.

## P0-11 — Integration test setup (Testcontainers)

- **Objective:** Isolated, disposable PostgreSQL for integration tests.
- **Files/components:** `integrationTest` source set + Gradle task,
  Testcontainers PostgreSQL dependency, base test class, integration tests
  for connectivity/Flyway/health/OpenAPI (from P0-05/06/09).
- **Dependencies:** P0-04, P0-05, P0-06, P0-09.
- **Verification method:** `./gradlew integrationTest` passes with Docker
  running; tests do not touch the Compose database.
- **Acceptance criteria:** Each test run uses a fresh container; tests are
  independent of local environment state.
- **Architecture impact:** None (test infrastructure only).

## P0-12 — Static analysis and formatting

- **Objective:** Enforceable formatting and static analysis.
- **Files/components:** Spotless plugin config (google-java-format),
  Checkstyle config, Gradle `check` wiring.
- **Dependencies:** P0-02.
- **Verification method:** `./gradlew spotlessCheck checkstyleMain
  checkstyleTest` passes; deliberately misformatted file fails the build.
- **Acceptance criteria:** `check` task includes format and static analysis
  gates.
- **Architecture impact:** None. Enforces "architecture must be enforceable"
  for style rules.

## P0-13 — Makefile

- **Objective:** Single entry point for local workflows.
- **Files/components:** `Makefile` with `build`, `up`, `down`, `test`,
  `integration-test`, `verify`.
- **Dependencies:** P0-02 through P0-12.
- **Verification method:** From a clean checkout: `make test` and
  `make verify` succeed with only JDK 21 + Docker installed.
- **Acceptance criteria:** `make verify` = format check + static analysis +
  compile + unit tests + integration tests; `make test` = unit +
  integration tests.
- **Architecture impact:** None.

## P0-14 — CI pipeline

- **Objective:** Automated verification of every change.
- **Files/components:** `.github/workflows/ci.yml`.
- **Dependencies:** P0-13 (CI calls the same make targets).
- **Verification method:** Push triggers pipeline; all six required stages
  (dependency install, compilation, unit tests, integration tests, static
  analysis, formatting verification) run green.
- **Acceptance criteria:** Pipeline fails if any stage fails; Gradle cache
  used; pipeline runs on hosted runners without manual infrastructure.
- **Architecture impact:** None.

## Dependency graph

```text
P0-01 ──> P0-02 ──> P0-03 ──> P0-05 ──> P0-06 ──> P0-11 ──> P0-13 ──> P0-14
              │         │        ▲                  ▲
              │         └───────>│P0-10              │
              │                  │                  │
P0-04 ────────┴─────────────────>│                  │
P0-02 ──> P0-07                  │                  │
P0-02 ──> P0-08 ───────────────────────────────────>│
P0-02 ──> P0-09 ───────────────────────────────────>│
P0-02 ──> P0-12 ───────────────────────────────────>│
```

Suggested execution order:
P0-01 → P0-02 → P0-03 → P0-04 → P0-05 → P0-06 → P0-07 → P0-08 → P0-09 →
P0-10 → P0-11 → P0-12 → P0-13 → P0-14.

---

# 5. Architecture Impact

| Task | Changes architecture? | Notes |
|---|---|---|
| P0-01 | No | Documentation and ADR only. |
| P0-02 | No | Establishes the single deployable the architecture already prescribes (modular monolith). |
| P0-03 | No | Configuration plumbing. |
| P0-04 | No | PostgreSQL already mandated as source of truth. |
| P0-05 | No | Migration tooling, not a data-architecture change. |
| P0-06 | No | Operational endpoint. |
| P0-07 | No | Logging format. |
| P0-08 | No | Metrics endpoint. |
| P0-09 | No | API documentation. |
| P0-10 | No | Test infrastructure. |
| P0-11 | No | Test infrastructure. |
| P0-12 | No | Enforcement tooling. |
| P0-13 | No | Task runner. |
| P0-14 | No | CI only. |

No task introduces Kafka, Redis, Kubernetes, microservices, CQRS,
Elasticsearch, event sourcing, or any business functionality. No new ADR is
required beyond P0-01's stack-decision record (which documents the already
made decision rather than changing architecture).

---

# 6. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Stack was a human decision, not a doc requirement.** If the owner changes their mind, all tasks are invalidated. | High | Recorded in ADR-0001 (P0-01) before any code. |
| **CI platform assumed GitHub Actions.** | Medium | Isolated to P0-14; swapping platforms touches one file. |
| **Testcontainers requires Docker locally and in CI.** Developers without Docker cannot run integration tests. | Medium | Documented prerequisite in README; `make test` clearly fails with actionable message. |
| **Java/Gradle/Spring Boot version drift.** Unpinned versions break reproducibility. | Medium | Gradle wrapper committed; versions pinned in build files; toolchain set to Java 21. |
| **Dev credentials in `compose.yaml`.** Risk of leaking into production habits. | Low | Dev-only defaults documented; production secrets out of scope for Phase 0; no secrets in logs enforced by P0-07. |
| **`make verify` performance.** Full gate including Testcontainers may be slow. | Low | Gradle build cache + CI caching; acceptable at Phase 0 scale. |
| **git history starts now.** Zero commits means no baseline. | Low | P0-01 creates the initial commit before code lands. |
| **Assumption: app not containerized in Phase 0.** If the owner expects a Docker image for the app, scope grows. | Low | Stated explicitly in this plan; defer app image to a later phase. |

---

# 7. Verification Strategy

Phase 0 verification mirrors its own acceptance criteria and Definition of
Done:

1. **Clean-checkout gate (primary).** On a fresh clone with only JDK 21,
   Docker, and Make installed:
   - `make test` — unit + integration tests pass (integration tests spin up
     isolated Testcontainers PostgreSQL).
   - `make verify` — formatting check, static analysis, compilation, unit
     tests, and integration tests all pass.
2. **Runtime smoke verification (documented in README):**
   - `make up` → PostgreSQL healthy via Compose healthcheck.
   - Start the app → `flyway_schema_history` shows the baseline migration;
     logs are structured JSON in non-local profile.
   - `GET /actuator/health/liveness` → UP.
   - `GET /actuator/health/readiness` → UP; goes DOWN when PostgreSQL is
     stopped (explicit failure semantics check).
   - `GET /actuator/prometheus` → metrics present.
   - `GET /v3/api-docs` → valid OpenAPI document.
   - SIGTERM → graceful shutdown completes without error.
3. **CI gate.** Every push runs the same six-stage pipeline as
   `make verify`; a green pipeline is required evidence.
4. **Traceability.** Each acceptance-criteria checkbox in
   `docs/bootcamp/phase-00.md` maps to at least one task in Section 4 with
   an automated verification method. Phase 0 is complete only when all
   checkboxes can be ticked with command output as evidence.
