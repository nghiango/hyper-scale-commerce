# Phase 05 — Service Extraction

Status: **APPROVED**

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-04-plan.md`
- `docs/bootcamp/evidence/p4-cqrs.md`
- `docs/bootcamp/evidence/p2-slo-verification.md`
- `docs/adr/0006-kafka-event-broker.md`
- `docs/adr/0007-transactional-outbox.md`
- `docs/adr/0008-cqrs-order-query-side.md`
- `docs/adr/0009-data-access-spring-data-jdbc-jooq.md`
- The existing Phase 4 implementation (build, source, tests, evidence)

---

## 1. Phase objective

Extract the first deployable service from the modular monolith: the Order
query side (read model, projection, and the `GET /orders*` endpoints) becomes
an independently deployable `order-query` service that consumes `OrderPlaced`
events from Kafka and serves reads from its own schema. The monolith keeps
`POST /orders`, Catalog, and Inventory. This makes the constitution's stage 6
(Service Extraction) concrete using the CQRS read model proven in Phase 4, and
proves the platform can run as two independently started deployables
communicating exclusively through events.

---

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. Modular Monolith
3. Performance Engineering
4. Event-Driven Architecture
5. CQRS
6. **Service Extraction** ← this phase
7. Resilience Engineering
8. ...

Phase 4 separated the Order read and write paths with CQRS and proved the
event-driven projection pattern, explicitly to prepare this phase ("later
phases (service extraction) need a proven read-model pattern before the query
side can be extracted"). The query side is the lowest-risk first extraction:
it is read-only, owns a denormalized read model, has a dedicated consumer
group (`order-query`), and never touches the write tables. Extracting it
proves independent deployment, per-service data ownership, per-service
migrations, and cross-service event flow — the capabilities Resilience
Engineering (Phase 6) and Observability (Phase 7) will build on.

---

## 3. Starting architecture / state

| Item | State |
|---|---|
| Build | Single Gradle module `app`; Kotlin 2.2, Spring Boot 4.0, JDK 21; jOOQ codegen from Flyway DDL (no DB at build time); spotless + detekt |
| Bounded contexts | Catalog (read-only), Order (command `POST /orders` + query `GET /orders*` from read model), Inventory (`order-placed` consumer), all as packages in `app` |
| Database | PostgreSQL 16 via Compose + Testcontainers; schemas `catalog`, `order`, `inventory`; migrations V1–V7 |
| Read model | `order.order_read_model` (order_id PK, status, items JSONB, created_at, updated_at), projected by `OrderPlacedProjection` (group `order-query`) |
| Event backbone | Kafka (KRaft, single node); topic `order-placed`; DLQ `order-placed-dlq`; consumer groups `inventory`, `order-query`; transactional outbox `order.outbox_events` with 1s relay |
| Event contract | `OrderPlacedEvent` JSON (`version`, `eventId`, `orderId`, `status`, `createdAt`, `items[]`) defined in `modules/order/application` |
| Data access | Spring Data JDBC (aggregates) + jOOQ (queries); no `JdbcTemplate` in repository implementations |
| API | `GET /catalog/products*`, `POST /orders`, `GET /orders/{id}`, `GET /orders?page=&size=` |
| Metrics | `events_published_total`, `events_consumed_total{consumer}`, `events_dlq_total`, `kafka_consumer_lag`, `outbox_relay_lag`, `order_read_model_lag_seconds` |
| Tests | JUnit 5, AssertJ, Testcontainers (PostgreSQL + Kafka), ArchUnit (catalog, order, inventory, shared) |
| Evidence | `p2-*`, `p3-reliability.md`, `p3-event-flow.md`, `p4-cqrs.md` |
| CI | GitHub Actions running `./gradlew build --no-daemon` |
| Docs | ADR-0001, 0002, 0006–0009 |

Phase 4's Definition of Done is assumed complete: the Phase 4 phase-review
has passed, `make verify` is green, and the Phase 4 review findings have been
addressed (see §15).

---

## 4. Target architecture / state

Two deployable units from one repository (monorepo, Gradle multi-module):

```text
app (monolith)                                order-query (service)
  POST /orders                                  GET /orders/{id}
  GET /catalog/products*                        GET /orders?page=&size=
  OrderPlacedProjection: removed                OrderPlacedProjection (group order-query)
  OrderService + outbox + relay                 OrderQueryService
  schemas: catalog, order, inventory            schema: order_query
        |                                              ^
        v                                              |
  order.outbox_events ──relay──> Kafka order-placed ───┘
                                     |
                                     └──> group: inventory (unchanged, in app)
```

Gradle modules:

```text
root
  app          # monolith: catalog, order command, inventory, shared outbox
  order-query  # extracted query service
  contracts    # event contracts (OrderPlacedEvent), depended on by both
```

- Each deployable owns its Flyway migrations and its jOOQ codegen input; the
  build remains database-free at build time.
- The read model moves from `order.order_read_model` to
  `order_query.order_read_model`, owned solely by the `order-query` service.
- The monolith no longer serves `GET /orders*` and no longer contains the
  projection or the query service.
- Both services expose their own actuator endpoints on distinct ports
  (`app`: 8080, `order-query`: 8081).
- Cross-service communication is exclusively Kafka events; there are no
  synchronous inter-service calls.

---

## 5. Problems this phase addresses

- The platform is a single deployable; independent deployment and the
  operational concerns of a distributed system (startup order, per-service
  health, per-service data ownership) are unproven.
- The Order query side shares the `order` schema with the command side; a
  physically extracted service must own its persistence to satisfy the
  constitution's data-ownership rule at the service level.
- The event contract lives inside the Order module; two deployables consuming
  it need a shared, versioned contract location.
- The build is a single module; compile-time module boundaries do not yet
  enforce what may be shared between deployables.

---

## 6. Architecture changes

- Split the build into Gradle modules: `app` (monolith), `order-query`
  (extracted service), `contracts` (event contracts).
- Move `OrderPlacedProjection`, `OrderQueryService`, the `GET /orders*`
  endpoints, and their ArchUnit rules from `app` to `order-query`.
- Move the read model migration to the `order-query` module, targeting a new
  `order_query` schema owned by the service; drop the read model from the
  monolith's migration set and jOOQ codegen.
- Extract `OrderPlacedEvent` (and `OrderPlacedItem`) into the `contracts`
  module; `app` (Order command + Inventory consumer) and `order-query` depend
  on it.
- Each service carries its own Spring Boot application entry point,
  configuration, Flyway migration location, jOOQ codegen configuration,
  Kafka consumer configuration (bounded retries + DLQ), and actuator
  endpoints.
- Add container images (Dockerfiles) for both services and Compose entries
  behind a `services` profile, so `make up` still starts only infrastructure
  by default.

---

## 7. Technology changes

- **Gradle multi-module build** — `settings.gradle.kts` includes `app`,
  `order-query`, `contracts`; shared build conventions are factored without
  introducing new plugins beyond the existing set.
- **Docker images for services** — JRE-based Dockerfiles (eclipse-temurin
  21-jre) packaging each service's bootJar; Docker is already an allowed
  technology.
- **Per-service Flyway + jOOQ codegen** — the existing DDL-based codegen
  pattern is applied per module with per-module migration directories.
- No new runtime technology is introduced. No Kubernetes, no API gateway, no
  service discovery, no service mesh, no Redis, no event sourcing.

---

## 8. Non-functional requirements

- `make verify` passes from a clean checkout and builds all modules; CI
  (unchanged `./gradlew build --no-daemon`) covers all modules.
- All new Kotlin code passes `spotlessCheck` and `detekt`.
- The `order-query` service starts and becomes healthy independently of the
  monolith; with no events to consume it serves an empty read model.
- The `order-query` service starts while Kafka is unavailable and catches up
  when the broker returns (consumer retry/backoff as in Phase 3/4).
- Catalog SLOs from Phase 2 are re-verified and must not regress (the Catalog
  code path is unchanged by this phase).
- The projection consumer remains idempotent in the extracted service.
- The query path continues to never read the write tables — now enforced by
  module boundaries in addition to ArchUnit.
- No forbidden technology is introduced.

---

## 9. Performance expectations

Phase 5 does not claim new performance targets. Existing expectations carry
over to the extracted service:

- `GET /orders/{id}` p95 under **100ms** at 100 concurrent requests (served by
  `order-query`).
- `GET /orders` p95 under **200ms** at 100 concurrent requests (served by
  `order-query`).
- End-to-end propagation (`POST /orders` → visible in `GET /orders/{id}`)
  remains around the relay interval (~1s) and is measured in the evidence
  report.
- `POST /orders` behavior must not regress; Catalog SLOs are re-verified.

---

## 10. Reliability expectations

- The read model is projected from durable Kafka events and remains
  rebuildable by replaying the topic (consumer group reset procedure is
  documented in the evidence report).
- The projection consumer remains idempotent (upsert keyed on `order_id`).
- Bounded retries and poison-message handling (DLQ `order-placed-dlq`) apply
  to the projection consumer in the extracted service.
- Startup-order independence: either service may start first; the outbox
  buffers events and the projection catches up.
- The transactional outbox and its relay stay with the command side and are
  unchanged.

---

## 11. Observability requirements

- Each service exposes `/actuator/health`, `/actuator/prometheus`, and
  `/v3/api-docs` on its own port.
- `order_read_model_lag_seconds` and `events_consumed_total{consumer="order-query"}`
  are emitted by the `order-query` service; `events_published_total` and
  `outbox_relay_lag` remain with the monolith.
- Each service's metrics are distinguishable by application name
  (`spring.application.name`).
- Structured JSON logging (non-local profiles) is preserved in both services.

---

## 12. Security considerations

- No authentication is introduced; endpoints remain public as in Phase 4.
- Extraction adds a second HTTP surface on a distinct port; actuator exposure
  stays limited to health and prometheus as today.
- The read model contains only order data already exposed by the API.
- Logs must not include full stack traces for expected client errors.

---

## 13. Data considerations

- `order_query.order_read_model` (`order_id` PK, `status`, `items` JSONB,
  `created_at`, `updated_at`) is owned solely by the `order-query` service;
  no other deployable reads or writes it.
- The monolith's schemas are unchanged except that `order.order_read_model`
  is removed from its migration set (the table was introduced in Phase 4 and
  has no production data in the BootCamp context).
- The write model (`order.orders`, `order.order_items`) remains the source of
  truth; the read model is derived and rebuildable from events.
- Each service runs only its own Flyway migrations; migration numbering is
  per-service (`order-query` starts at V1 in its own history).
- Event payloads keep the `version` field; no contract change in this phase.

---

## 14. Explicitly out-of-scope capabilities

- Extracting Catalog or Inventory as services (later phases may).
- Kubernetes, API gateway, service discovery, service mesh.
- Separate physical databases per service (one PostgreSQL instance, separate
  schemas per deployable).
- Synchronous inter-service calls (REST/gRPC) between deployables.
- Event sourcing; exactly-once delivery.
- Changes to the Catalog, Order command, or Inventory business logic.
- Distributed tracing and dashboards (Phase 7/8 concerns).

---

## 15. Dependencies on the previous phase

Phase 5 depends on the successful completion of Phase 4, specifically:

- P4-01 through P4-08 are complete and verified, and the Phase 4 phase-review
  has passed.
- `make verify` passes from a clean checkout.
- The Phase 4 review findings are addressed before implementation begins:
  - `order_read_model_lag_seconds` and
    `events_consumed_total{consumer="order-query"}` assertions are added to
    `ApplicationIntegrationTest` (metric exposure coverage).
  - The Phase 2–4 work is committed (phase-boundary commits).
- `docs/bootcamp/current-phase.md` is advanced to Phase 05 before
  implementation begins.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Module-split build breakage** (codegen, test source sets, detekt/spotless wiring) | High | Extract module conventions first (P5-02) with `make verify` green before any code moves; move code in small steps. |
| **Shared-code drift between deployables** | Medium | Only `contracts` is shared; event changes stay additive with `version` retained; single repo keeps both sides in lockstep. |
| **jOOQ codegen duplication** across two modules | Medium | Reuse the Phase 4 DDL-based pattern per module; per-module migration directories keep inputs isolated. |
| **Cross-service test complexity** (two Spring contexts) | Medium | Boot both applications in one test JVM against shared Testcontainers; keep the e2e test single and focused. |
| **Startup-order coupling** | Low | Outbox buffers events; consumer retries; verified by the e2e startup test. |
| **Scope creep toward k8s/gateway** | Medium | Explicitly deferred (§14); Compose profile is the deployment proof for this phase. |

---

## 17. ADRs that may be required

- **ADR-0010 — Extract the Order query side as the first service.** Required:
  service extraction. Documents the extraction candidate (alternatives:
  Catalog, Inventory, keep the monolith), the event-only communication model,
  and the consistency consequences.
- **ADR-0011 — Monorepo module and per-service data-ownership model.**
  Required: build/deployment and database architecture change. Documents the
  Gradle module split, the shared `contracts` module, per-service Flyway +
  jOOQ codegen, the `order_query` schema ownership decision (alternative:
  share the `order` schema between deployables), and the container/Compose
  model.

---

## 18. Ordered implementation tasks

### P5-01 — ADRs: service extraction and module/data-ownership model

- **Objective:** Record the architectural decisions for the first service
  extraction before any code is moved.
- **Context:** AGENTS.md requires ADRs for service extraction and database
  architecture changes.
- **Dependencies:** Phase 4 complete, phase-review passed, findings addressed.
- **Scope:** Documentation only.
- **Implementation requirements:**
  - Create ADR-0010 (extract the Order query side as the first service),
    covering alternatives (Catalog, Inventory, keep the monolith) and the
    event-only communication model.
  - Create ADR-0011 (module and data-ownership model), covering the Gradle
    module split, the `contracts` module, per-service Flyway/jOOQ, the
    `order_query` schema decision, and the container/Compose model.
- **Acceptance criteria:**
  - ADR-0010 and ADR-0011 exist under `docs/adr/` and are accepted.
  - No technology beyond the existing stack (Gradle multi-module, Docker) is
    introduced.
- **Verification requirements:** Document review; no automated tests.
- **Expected files/components:**
  - `docs/adr/0010-extract-order-query-service.md`
  - `docs/adr/0011-monorepo-module-data-ownership.md`
- **Architecture impact:** Records the phase's architecture decisions.
- **Out of scope:** Implementation.

### P5-02 — Gradle multi-module split and contracts extraction

- **Objective:** Establish the module structure and the shared event contract
  with zero behavior change.
- **Context:** All later tasks depend on a green multi-module build.
- **Dependencies:** P5-01.
- **Scope:** `settings.gradle.kts`, `app/build.gradle.kts`, new
  `contracts/build.gradle.kts`; shared build convention wiring.
- **Implementation requirements:**
  - Add the `contracts` module containing `OrderPlacedEvent` and
    `OrderPlacedItem` (moved from `modules/order/application`).
  - `app` depends on `contracts`; all existing references (Order command,
    Inventory consumer, projection) use the contract from `contracts`.
  - spotless, detekt, and test wiring work for all modules; `make verify`
    passes with no behavior change.
- **Acceptance criteria:**
  - `make verify` passes; all existing tests pass unchanged.
  - The event contract is defined once in `contracts`.
- **Verification requirements:** Run `make verify`.
- **Expected files/components:**
  - `settings.gradle.kts`
  - `contracts/build.gradle.kts`
  - `contracts/src/main/kotlin/.../contracts/OrderPlacedEvent.kt`
- **Architecture impact:** Build structure; no runtime change.
- **Out of scope:** The `order-query` module; code moves beyond the contract.

### P5-03 — order-query service skeleton

- **Objective:** A bootable, healthy `order-query` service with its own
  configuration, migrations, and observability — no business code yet.
- **Context:** Establishes the second deployable before the query code moves.
- **Dependencies:** P5-02.
- **Scope:** New `order-query` module.
- **Implementation requirements:**
  - Spring Boot application entry point, `application.yml` (port 8081,
    application name `order-query`), logback configuration.
  - Flyway configured against the module's own migration directory
    (`order_query` schema); jOOQ codegen wired from those migrations.
  - Actuator health/prometheus/api-docs exposed; Kafka consumer configuration
    with bounded retries and DLQ mirroring the Phase 3 pattern.
  - Integration test: the service boots, connects to PostgreSQL and Kafka,
    and reports healthy without the monolith running.
- **Acceptance criteria:**
  - The service starts and is healthy with only PostgreSQL + Kafka running.
  - `make verify` passes.
- **Verification requirements:** Run the skeleton integration test; run
  `make verify`.
- **Expected files/components:**
  - `order-query/build.gradle.kts`
  - `order-query/src/main/kotlin/.../OrderQueryApplication.kt`
  - `order-query/src/main/resources/application.yml`
  - `order-query/src/main/resources/db/migration/V1__order_query_read_model.sql`
- **Architecture impact:** Adds the second deployable.
- **Out of scope:** Moving the projection, query service, or endpoints.

### P5-04 — Move the read model, projection, and query API to the service

- **Objective:** `order-query` serves `GET /orders*` from its own schema.
- **Context:** The query components are proven in Phase 4; this task
  relocates them.
- **Dependencies:** P5-03.
- **Scope:** `order-query` module (and the migration relocation).
- **Implementation requirements:**
  - Move `OrderPlacedProjection`, `OrderQueryService`, `PagedOrdersDto`,
    `OrderDto` query usage, and the `GET /orders*` endpoints (controller +
    error handler) into `order-query`.
  - Move the read-model migration into the service targeting
    `order_query.order_read_model`; the service's jOOQ codegen covers it.
  - Move the projection and query integration tests
    (`OrderProjectionIntegrationTest`, the read-model tests from
    `OrderControllerIntegrationTest`) to the service.
  - Move the `order_read_model_lag_seconds` gauge and the
    `events_consumed_total{consumer="order-query"}` counter with the
    projection; metric assertions travel with the moved tests.
  - ArchUnit rules for the moved components travel with them.
- **Acceptance criteria:**
  - `GET /orders/{id}` and `GET /orders` are served by `order-query` from
    `order_query.order_read_model`; replay idempotency tests pass in the
    service module.
  - `make verify` passes.
- **Verification requirements:** Run the service's tests; run `make verify`.
- **Expected files/components:**
  - `order-query/src/main/kotlin/.../application/`, `.../api/`
  - `order-query/src/integrationTest/kotlin/...`
- **Architecture impact:** The query side is owned by the service.
- **Out of scope:** Removing the query side from the monolith (P5-05).

### P5-05 — Remove the query side from the monolith

- **Objective:** The monolith keeps only the command side, Catalog, and
  Inventory.
- **Context:** Completes the separation; prevents two owners of the read
  path.
- **Dependencies:** P5-04.
- **Scope:** `app` module.
- **Implementation requirements:**
  - Remove `OrderPlacedProjection`, `OrderQueryService`, the `GET /orders*`
    endpoints, the read-model migration (V7), and the read-model jOOQ
    references from `app`.
  - `OrderController` in `app` keeps only `POST /orders`; remove the
    read-model gauge bean from `app`.
  - Update ArchUnit rules in `app` (the CQRS query rule moves with the
    service; monolith rules cover command/catalog/inventory/shared).
  - Update `ApplicationIntegrationTest` migration-count assertions and the
    metric assertions for metrics that moved to the service.
- **Acceptance criteria:**
  - The monolith compiles and all its tests pass; `GET /orders*` is no longer
    served by `app` (404).
  - `make verify` passes.
- **Verification requirements:** Run the monolith tests; run `make verify`.
- **Expected files/components:**
  - `app/src/main/kotlin/.../modules/order/api/OrderController.kt`
  - `app/src/main/kotlin/.../modules/order/application/OrderService.kt`
- **Architecture impact:** Completes the extraction.
- **Out of scope:** Deployment wiring (P5-06).

### P5-06 — Container images and Compose wiring

- **Objective:** Both services are runnable as containers from Compose.
- **Context:** Proves independent deployability on the local stack.
- **Dependencies:** P5-05.
- **Scope:** Dockerfiles, `compose.yaml`, `Makefile`, README.
- **Implementation requirements:**
  - Add a Dockerfile per service (JRE 21 base, service bootJar).
  - Add `app` and `order-query` Compose services behind a `services` profile,
    depending on healthy PostgreSQL and Kafka, with ports 8080/8081 and the
    required environment variables; `make up` unchanged by default.
  - Document running both services (bootRun per module and the Compose
    profile) in the README.
- **Acceptance criteria:**
  - `docker compose --profile services up` starts both services healthy; the
    order flow works end-to-end across the two containers.
  - `make verify` passes.
- **Verification requirements:** Manual Compose verification recorded in the
  evidence report; run `make verify`.
- **Expected files/components:**
  - `app/Dockerfile`
  - `order-query/Dockerfile`
  - `compose.yaml`
  - `Makefile`, `README.md`
- **Architecture impact:** Deployment topology.
- **Out of scope:** Kubernetes or any orchestration beyond Compose.

### P5-07 — Cross-service end-to-end test

- **Objective:** Prove the distributed flow and startup-order independence.
- **Context:** Final functional proof of the extraction.
- **Dependencies:** P5-06.
- **Scope:** `app` integration tests (test-only dependency on `order-query`
  classes to boot both applications in one test JVM).
- **Implementation requirements:**
  - E2E test boots the monolith and `order-query` against shared
    Testcontainers (PostgreSQL + Kafka): `POST /orders` on the monolith →
    projection in `order-query` → `GET /orders/{id}` from `order-query`;
    replay the event and verify idempotency.
  - Startup-order case: `order-query` healthy before the monolith starts, and
    the flow succeeds once both are up.
  - Measure `GET /orders/{id}` and `GET /orders` under a small concurrent
    load against the service and record p95 for the evidence report.
- **Acceptance criteria:**
  - The e2e test passes, including the startup-order case and the replay
    idempotency check.
  - `make verify` passes.
- **Verification requirements:** Run the e2e test; run `make verify`.
- **Expected files/components:**
  - `app/src/integrationTest/kotlin/.../ServiceExtractionE2ETest.kt`
- **Architecture impact:** None; verification.
- **Out of scope:** Failure/chaos scenarios beyond startup order (Phase 6/10
  concerns).

### P5-08 — Evidence capture and phase gate

- **Objective:** Capture the extraction evidence and close the phase.
- **Context:** AGENTS.md requires evidence-backed claims.
- **Dependencies:** P5-07.
- **Scope:** `docs/bootcamp/evidence/p5-service-extraction.md`.
- **Implementation requirements:**
  - The report documents the two-service topology, the event flow across
    deployables, timings (POST→read-model-visible), replay idempotency,
    startup-order independence, measured query p95, and the catalog SLO
    re-verification.
  - The report documents the read-model rebuild procedure (consumer group
    reset / topic replay).
  - Record the Compose verification from P5-06.
- **Acceptance criteria:**
  - The evidence report exists and covers all of the above.
  - `make verify` passes.
- **Verification requirements:** Review the report; run `make verify`.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p5-service-extraction.md`
- **Architecture impact:** None; documentation.
- **Out of scope:** Advancing to Phase 6.

### Dependency graph

```text
P5-01 ──> P5-02 ──> P5-03 ──> P5-04 ──> P5-05 ──> P5-06 ──> P5-07 ──> P5-08
```

### Suggested execution order

P5-01 → P5-02 → P5-03 → P5-04 → P5-05 → P5-06 → P5-07 → P5-08

---

## 19. Phase exit criteria

Phase 5 is complete only when all of the following are true:

1. All tasks P5-01 through P5-08 are implemented and verified.
2. `make verify` passes from a clean checkout with no manual intervention
   beyond JDK 21, Docker, and Make.
3. The CI pipeline is green for the changes and builds all modules.
4. Two independently deployable services exist; `order-query` starts and is
   healthy without the monolith, and the flow works regardless of startup
   order.
5. `GET /orders/{id}` and `GET /orders` are served by `order-query` from
   `order_query.order_read_model`; the monolith no longer serves them and no
   longer contains the projection.
6. Cross-service communication happens exclusively through Kafka events;
   there are no synchronous inter-service calls and no shared writes across
   deployables.
7. The projection remains idempotent in the extracted service (replay does
   not duplicate or corrupt the read model).
8. Per-service observability: each service exposes health/prometheus/api-docs;
   `order_read_model_lag_seconds` and
   `events_consumed_total{consumer="order-query"}` are emitted by
   `order-query` and asserted by tests.
9. Catalog SLOs from Phase 2 are re-verified and do not regress.
10. The evidence report `docs/bootcamp/evidence/p5-service-extraction.md`
    documents the flow, timings, idempotency, startup-order independence,
    measured p95, and the rebuild procedure.
11. No forbidden technology (Kubernetes, API gateway, service mesh, Redis,
    event sourcing) has been introduced.
12. Git diff is clean and no unrelated files are modified.
13. The phase review process has been passed before `current-phase.md` is
    updated to Phase 06.
