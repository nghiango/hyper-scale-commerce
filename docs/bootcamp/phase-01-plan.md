# Phase 01 — Modular Monolith with Catalog

Status: **PROPOSED** — not yet approved, not yet implemented.

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/phase-00.md`
- `docs/bootcamp/phase-00-plan.md`
- `docs/adr/0001-technology-stack.md`
- The existing Phase 0 implementation (build, source, tests, CI)

---

## 1. Phase objective

Transform the Phase 0 engineering foundation into the first increment of a
modular monolith by implementing the **Catalog** bounded context with
package-level module boundaries, a minimal product domain, a read-only REST API,
and architecture tests that enforce dependency direction. This phase
establishes the conventions for adding the remaining bounded contexts later
without rewriting the application skeleton.

---

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. **Modular Monolith** ← this phase
3. Performance Engineering
4. Event-Driven Architecture
5. ...

Phase 0 built the foundation. Phase 1 must prove that the platform can host a
real bounded context while preserving the single-deployable, single-database
modular-monolith target drawn in `docs/architecture.md`. Catalog is chosen first
because it is the entry point of the commerce domain and has no hard dependency
on any other bounded context.

---

## 3. Starting architecture / state

| Item | State |
|---|---|
| Application | Single Kotlin/Spring Boot `app` module; no business code |
| Root package | `com.hyperscale.commerce` with only `config` |
| Database | PostgreSQL 16 via Docker Compose; `V1__baseline.sql` applied |
| Data access | `spring-boot-starter-jdbc` + `JdbcTemplate` available |
| Tests | JUnit 5, AssertJ, Testcontainers PostgreSQL, spotless, detekt |
| CI | GitHub Actions running `./gradlew build` |
| Docs | ADR-0001 records stack; README documents Phase 0 workflow |

Phase 0's Definition of Done is assumed complete: `make test` and `make verify`
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

The single `app` module is internally partitioned by package:

```text
com.hyperscale.commerce
  modules
    catalog
      domain          # Product, SKU, Price, ProductRepository interface
      application     # CatalogService, DTOs
      infrastructure  # JdbcProductRepository, RowMapper
      api             # CatalogController
```

New public REST endpoints under `/catalog/products`.

New Flyway migration `V2__catalog_product.sql` creating a `catalog` PostgreSQL
schema and `products` table.

ArchUnit tests enforce:

- `catalog.domain` does not depend on `catalog.application`,
  `catalog.infrastructure`, or `catalog.api`
- `catalog.application` depends only on `catalog.domain`
- `catalog.api` depends only on `catalog.application`
- `catalog.infrastructure` implements `catalog.domain` interfaces
- No package outside `catalog` depends on `catalog.*.internal` packages

---

## 5. Problems this phase addresses

- The repository has no business functionality.
- The modular monolith target in `docs/architecture.md` is not yet demonstrated.
- No bounded-context ownership of data or code has been exercised.
- There are no conventions for how Customer, Cart, Order, etc. should be added.
- The OpenAPI spec is empty of business operations.

---

## 6. Architecture changes

- Introduce package-level bounded-context modules inside `app`.
- Establish the dependency direction: `api → application → domain`.
- Implement `ProductRepository` interface in `domain`; concrete repository in
  `infrastructure`.
- Catalog owns the `catalog.product` table; other contexts may not access it.
- REST controllers live in `catalog.api`; they use only `CatalogService`.
- Add architecture tests to make the above rules machine-enforceable.

---

## 7. Technology changes

- **No new runtime infrastructure.** PostgreSQL remains the only data store.
- Continue using `spring-boot-starter-jdbc` and `JdbcTemplate` for persistence.
- **One optional test-only library:** ArchUnit (architecture testing). If
  selected, it is a test-scoped dependency only and does not require an ADR.
- No JPA, no Spring Data JDBC, no Redis, no Kafka, no Kubernetes, no
  Elasticsearch, no event sourcing, no CQRS, no service mesh.

If the implementation team later decides to introduce a data-access framework
beyond `JdbcTemplate` (e.g. Spring Data JPA or Spring Data JDBC), that requires
ADR-0002.

---

## 8. Non-functional requirements

- All existing `make test` and `make verify` checks continue to pass.
- Every new catalog repository method has at least one integration test running
  against Testcontainers.
- Every catalog API endpoint has at least one integration test.
- All new Kotlin code passes `spotlessCheck` and `detekt`.
- Invalid input returns HTTP `400`; missing products return `404`; unhandled
  exceptions return `500` and are logged.
- The application must still start with `make up` + `SPRING_PROFILES_ACTIVE=local`.

---

## 9. Performance expectations

Phase 1 does **not** claim the final `< 200ms p95` target. Local expectations:

- `GET /catalog/products/{id}` under **100ms** for a warm database.
- `GET /catalog/products?query=&page=&size=` under **1s** for a catalog of up to
  1,000 products with a default page size of 20.
- Metrics for catalog endpoints must be visible at `/actuator/prometheus`.
- No formal load testing is required in this phase.

---

## 10. Reliability expectations

- The application starts only when `readinessState` and `db` health indicators
  are `UP`.
- Flyway applies `V2__catalog_product.sql` automatically and idempotently.
- Catalog endpoints remain available while PostgreSQL is healthy.
- All SQL queries use parameterized statements; no string concatenation of user
  input.
- The phase must not degrade any Phase 0 health, metric, or OpenAPI endpoint.

---

## 11. Observability requirements

- Existing structured JSON logging continues for non-local profiles.
- Existing `/actuator/health`, `/actuator/prometheus`, and `/v3/api-docs`
  endpoints continue to work.
- Catalog REST operations are automatically timed and counted by Micrometer/Spring.
- Errors returning `4xx` and `5xx` are logged with the URI and a correlation ID
  generated by the framework where available.

---

## 12. Security considerations

- Catalog endpoints are public and read-only; no authentication in this phase.
- All query parameters are validated and bound with parameterized SQL.
- Catalog data contains no PII, but logs must not include full stack traces for
  `4xx` client errors at INFO level.
- CORS is not configured; the API is assumed to be same-origin for now.
- SKU and search strings are treated as opaque text; no HTML/rendering is
  returned that could enable XSS through the API.

---

## 13. Data considerations

- Catalog owns the `catalog` PostgreSQL schema and the `catalog.products` table.
- The table holds: id, sku, name, description, price (as integer smallest
  currency unit), availability, created_at, updated_at.
- No other bounded context may read or write catalog tables directly; cross
  context access will later occur through explicit interfaces.
- A minimal seed data set may be added for local development and integration
  tests, but seed data belongs in `infrastructure` and is not required in
  production.

---

## 14. Explicitly out-of-scope capabilities

- Customer, Cart, Order, Inventory, Payment, Shipping, Notification bounded
  contexts.
- User registration, authentication, authorization, sessions, or JWT.
- Product administration (create/update/delete products).
- Inventory reservation or stock decrements.
- Pricing rules, discounts, taxes, or currency conversion.
- Product categories, variants, images, or reviews.
- Search indexing, full-text search, or autocomplete beyond a simple
  case-insensitive substring match.
- Caching (Redis or otherwise).
- Asynchronous messaging, events, sagas, or outbox patterns.
- Service extraction or separate deployables.
- Application containerization beyond the existing Compose PostgreSQL service.

---

## 15. Dependencies on Phase 0

Phase 1 depends on the successful completion of Phase 0, specifically:

- P0-01 through P0-14 are complete and verified.
- `make test` and `make verify` pass from a clean checkout.
- CI pipeline is green.
- PostgreSQL, Flyway, Testcontainers, health, metrics, OpenAPI, and logging are
  in place and functional.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Module boundary over-engineering** | Medium | Use package modules only; no Gradle subprojects in this phase. |
| **Search query performance with `ILIKE` on large catalogs** | Medium | Cap page size; limit catalog to seed data; full-text search is explicitly out of scope. |
| **JdbcTemplate verbosity** | Low | Keep repository small; consider ADR-0002 if a different data-access style is needed. |
| **Testcontainers slowdown as tests grow** | Low | Reuse the single `@ServiceConnection` container pattern from Phase 0. |
| **Other contexts begin to depend on catalog internals** | Medium | ArchUnit architecture tests fail the build on illegal dependencies. |

---

## 17. ADRs that may be required

- **ADR-0002 — Catalog data ownership and schema (optional)**. Required only if
  the implementation team decides to create a separate PostgreSQL schema for
  Catalog or to change data-access technology beyond the existing
  `spring-boot-starter-jdbc`. If the schema is kept inside `public` and
  `JdbcTemplate` is used, an ADR is not strictly required, but recording the
  decision is still recommended.
- **ADR-0003 — Package module boundaries (optional)**. Required only if the team
  chooses a non-package module strategy (e.g. Gradle subprojects or separate
  source sets). The package-module convention itself is already implied by the
  modular-monolith target in `docs/architecture.md`.

---

## 18. Ordered implementation tasks

### P1-01 — Define Catalog bounded-context boundaries and data-access approach

- **Objective:** Document and agree on the package layout, dependency
  directions, and data-access style before any code is written.
- **Context:** Phase 0 has no business code. The first bounded context must set
  conventions for all future contexts.
- **Dependencies:** Phase 0 complete.
- **Scope:** Planning and ADR writing only; no source changes.
- **Implementation requirements:**
  - Choose `com.hyperscale.commerce.modules.catalog` package root with
    `domain`, `application`, `infrastructure`, and `api` sub-packages.
  - Decide whether to use a `catalog` PostgreSQL schema or stay in `public`.
  - Confirm `JdbcTemplate` remains the data-access tool for this phase.
  - Write or update ADR-0002 if any technology beyond the existing stack is
    selected.
- **Acceptance criteria:**
  - Package layout is documented in this plan or an ADR.
  - Data-access decision is recorded if it deviates from `spring-boot-starter-jdbc`.
  - A reviewer approves the boundaries.
- **Verification requirements:** Document review; no automated tests.
- **Expected files/components:** `docs/adr/0002-catalog-data-ownership.md` (if
  needed), updated `docs/architecture.md` or README note.
- **Architecture impact:** Establishes the package-module convention and data
  ownership for Catalog.
- **Out of scope:** Writing domain classes, controllers, or SQL.

### P1-02 — Create Catalog package structure and architecture tests

- **Objective:** Add the package skeleton and a failing ArchUnit test that
  enforces dependency direction before business code exists.
- **Context:** The package layout from P1-01 must be materialized and guarded.
- **Dependencies:** P1-01.
- **Scope:** Package directories, build dependency, and one architecture test.
- **Implementation requirements:**
  - Create the package tree under `com.hyperscale.commerce.modules.catalog`.
  - Add ArchUnit as a test dependency.
  - Write `CatalogArchitectureTest.kt` asserting the dependency rules in
    Section 4.
- **Acceptance criteria:**
  - `make verify` passes.
  - A deliberately misplaced dependency (e.g. `domain` importing `infrastructure`)
    fails the build.
- **Verification requirements:** Run `make verify`; run a negative test by
  temporarily violating a rule.
- **Expected files/components:**
  - `app/src/test/kotlin/com/hyperscale/commerce/modules/catalog/CatalogArchitectureTest.kt`
  - `app/build.gradle.kts` (ArchUnit test dependency)
  - Empty placeholder classes in each package (optional)
- **Architecture impact:** Enforces package-level module boundaries.
- **Out of scope:** Persistence, domain logic, REST endpoints.

### P1-03 — Catalog database schema

- **Objective:** Add the first business migration for the Catalog
  products table.
- **Context:** Catalog needs a persistence model. Flyway already runs
  migrations automatically on startup.
- **Dependencies:** P1-01.
- **Scope:** One Flyway migration file and a smoke test.
- **Implementation requirements:**
  - Create `app/src/main/resources/db/migration/V2__catalog_product.sql`.
  - Define `catalog.products` (or `public.products` if schema decision stays in
    `public`) with an appropriate primary key and indexes for SKU and name.
- **Acceptance criteria:**
  - `make up` + `make run` creates the table.
  - `flyway_schema_history` contains `2`.
  - Integration test can insert and select from the table.
- **Verification requirements:** `ApplicationIntegrationTest` or a new
  `CatalogMigrationTest` confirms the migration runs.
- **Expected files/components:**
  - `app/src/main/resources/db/migration/V2__catalog_product.sql`
- **Architecture impact:** Catalog owns its persistence model.
- **Out of scope:** Seed data, application queries.

### P1-04 — Catalog domain model

- **Objective:** Implement the core Catalog value objects and entity.
- **Context:** Domain is the innermost layer; it must not reference Spring,
  JdbcTemplate, or HTTP concerns.
- **Dependencies:** P1-02 (package structure), P1-03 (schema informs types).
- **Scope:** `Product`, `ProductId`, `Sku`, `Money`, `Availability`, and the
  `ProductRepository` interface.
- **Implementation requirements:**
  - Place classes in `com.hyperscale.commerce.modules.catalog.domain`.
  - `ProductRepository` is an interface with operations: find by id, search by
    name/SKU with pagination, find by SKU.
  - Validation invariants (e.g. SKU non-empty, price non-negative) inside the
    domain.
- **Acceptance criteria:**
  - Unit tests for creation and validation invariants pass.
  - ArchUnit confirms `domain` has no `infrastructure` or `api` imports.
  - `make test` passes.
- **Verification requirements:** Unit tests and ArchUnit.
- **Expected files/components:**
  - `app/src/main/kotlin/com/hyperscale/commerce/modules/catalog/domain/*`
  - `app/src/test/kotlin/com/hyperscale/commerce/modules/catalog/domain/*`
- **Architecture impact:** Defines the Catalog entity model and repository
  contract.
- **Out of scope:** Persistence implementation, REST serialization.

### P1-05 — Catalog repository implementation

- **Objective:** Implement `ProductRepository` using the existing `JdbcTemplate`
  and Spring `NamedParameterJdbcTemplate` if needed.
- **Context:** Persistence belongs in `infrastructure` and must implement the
  `domain` interface.
- **Dependencies:** P1-04.
- **Scope:** Repository, row mapper, SQL queries.
- **Implementation requirements:**
  - Implement `JdbcProductRepository` in `catalog.infrastructure`.
  - Use parameterized SQL for `findById`, `findBySku`, `search`.
  - Implement offset/limit pagination.
- **Acceptance criteria:**
  - Integration tests for find, search, and pagination pass against
    Testcontainers.
  - `make verify` passes.
- **Verification requirements:** Integration tests in `integrationTest` source
  set; parameterized query review.
- **Expected files/components:**
  - `app/src/main/kotlin/com/hyperscale/commerce/modules/catalog/infrastructure/*`
  - `app/src/integrationTest/kotlin/com/hyperscale/commerce/modules/catalog/*`
- **Architecture impact:** Realizes the Catalog persistence contract without
  exposing JdbcTemplate to the domain.
- **Out of scope:** Full-text search, admin writes.

### P1-06 — Catalog application service

- **Objective:** Provide the use-case layer between the REST API and the
  repository.
- **Context:** The `api` package must not call the repository directly.
- **Dependencies:** P1-05.
- **Scope:** `CatalogService` and read-only DTOs.
- **Implementation requirements:**
  - Create `CatalogService` in `catalog.application`.
  - Expose: list products (paginated, optional search), get product by id, get
    product by SKU.
  - Map domain entities to DTOs for the API layer.
  - Throw domain exceptions for not-found cases.
- **Acceptance criteria:**
  - Unit tests for `CatalogService` pass.
  - `CatalogService` depends only on `ProductRepository` interface and domain
    classes.
  - ArchUnit passes.
- **Verification requirements:** Unit tests and ArchUnit.
- **Expected files/components:**
  - `app/src/main/kotlin/com/hyperscale/commerce/modules/catalog/application/*`
  - `app/src/test/kotlin/com/hyperscale/commerce/modules/catalog/application/*`
- **Architecture impact:** Mediates between presentation and persistence.
- **Out of scope:** Business rules beyond simple read and mapping.

### P1-07 — Catalog REST API

- **Objective:** Expose the Catalog endpoints.
- **Context:** Controllers live in `catalog.api` and use `CatalogService`.
- **Dependencies:** P1-06.
- **Scope:** `CatalogController` and `catalog.api.*`.
- **Implementation requirements:**
  - `GET /catalog/products?page=&size=&query=` — list with optional search.
  - `GET /catalog/products/{id}` — get one product.
  - `GET /catalog/products/sku/{sku}` — get one product by SKU.
  - `GET /catalog/products/{id}/availability` — return availability.
  - Validate page/size parameters and return `400` for invalid values.
- **Acceptance criteria:**
  - All endpoints return `200` with correct JSON for valid inputs.
  - Unknown ids/SKUs return `404`.
  - Invalid parameters return `400`.
  - OpenAPI spec at `/v3/api-docs` contains the catalog paths.
- **Verification requirements:** Integration tests using JDK `HttpClient`;
  OpenAPI validation.
- **Expected files/components:**
  - `app/src/main/kotlin/com/hyperscale/commerce/modules/catalog/api/*`
  - `app/src/integrationTest/kotlin/com/hyperscale/commerce/modules/catalog/*`
- **Architecture impact:** Defines the public Catalog API surface.
- **Out of scope:** Admin endpoints, mutation endpoints.

### P1-08 — Error handling and validation

- **Objective:** Provide consistent, observable error responses for Catalog
  endpoints.
- **Context:** Phase 0 does not have business endpoints; error handling must be
  added for the new API.
- **Dependencies:** P1-07.
- **Scope:** Controller advice or controller-level error mapping.
- **Implementation requirements:**
  - Map catalog-specific domain exceptions (e.g. `ProductNotFoundException`) to
    `404`.
  - Map `IllegalArgumentException` and validation failures to `400`.
  - Return a small error body (`{ "error": "..." }` or problem detail).
  - Log `5xx` errors at ERROR; log `4xx` at DEBUG/INFO without stack trace
    pollution.
- **Acceptance criteria:**
  - Negative integration tests for `404` and `400` pass.
  - `500` errors produce JSON error bodies and are logged.
  - `make verify` passes.
- **Verification requirements:** Negative integration tests.
- **Expected files/components:**
  - `app/src/main/kotlin/com/hyperscale/commerce/modules/catalog/api/...ErrorHandler...`
- **Architecture impact:** Operational behavior; no architecture change.
- **Out of scope:** Global problem-details RFC, i18n.

### P1-09 — OpenAPI and documentation

- **Objective:** Ensure the OpenAPI spec and README reflect the new Catalog
  capabilities.
- **Context:** springdoc-openapi is already configured.
- **Dependencies:** P1-07.
- **Scope:** Annotations and README update.
- **Implementation requirements:**
  - Annotate `CatalogController` with summary/description tags.
  - Group catalog endpoints under a "Catalog" tag.
  - Update `README.md` to mention the catalog endpoints and Phase 1 plan link.
- **Acceptance criteria:**
  - `/v3/api-docs` includes catalog paths and schemas.
  - `/swagger-ui.html` displays the Catalog tag.
  - README references `docs/bootcamp/phase-01-plan.md`.
- **Verification requirements:** Integration test for `/v3/api-docs`; manual
  README review.
- **Expected files/components:**
  - `README.md`
  - Controller OpenAPI annotations
- **Architecture impact:** None.
- **Out of scope:** Generated API client, public documentation site.

### P1-10 — Phase 1 final verification

- **Objective:** Confirm the entire phase is complete and ready for phase
  review.
- **Context:** Last task before phase review.
- **Dependencies:** P1-08 and P1-09.
- **Scope:** Run all gates and gather evidence.
- **Implementation requirements:**
  - Run `make clean && make verify` from a fresh checkout.
  - Review git diff for unrelated changes.
  - Confirm all catalog tasks have passing tests and ArchUnit passes.
  - Update `docs/bootcamp/current-phase.md` **only if** phase review has
    already passed (do not change the phase status before review).
- **Acceptance criteria:**
  - `make verify` passes with all new and existing tests.
  - No unrelated files modified.
  - Phase exit criteria are met.
- **Verification requirements:** Command output, git status, manual checklist.
- **Expected files/components:** None new.
- **Architecture impact:** None.
- **Out of scope:** Phase 1 implementation beyond verification; advancing to
  Phase 2.

### Dependency graph

```text
P1-01 ──> P1-02 ──> P1-04 ──> P1-05 ──> P1-06 ──> P1-07 ──> P1-08 ──> P1-09 ──> P1-10
  │         │         ▲                                    
  │         │         │                                     
  └─────────┴───────> P1-03                                 
```

### Suggested execution order

P1-01 → P1-02 → P1-03 → P1-04 → P1-05 → P1-06 → P1-07 → P1-08 → P1-09 → P1-10

---

## 19. Phase exit criteria

Phase 1 is complete only when all of the following are true:

1. All tasks P1-01 through P1-10 are implemented and verified.
2. `make verify` passes from a clean checkout with no manual intervention beyond
   JDK 21, Docker, and Make.
3. The CI pipeline is green for the changes.
4. Catalog endpoints respond correctly under `SPRING_PROFILES_ACTIVE=local`:
   - `GET /catalog/products` returns a paginated list.
   - `GET /catalog/products/{id}` returns a product.
   - `GET /catalog/products/{id}/availability` returns availability.
   - Unknown ids/SKUs return `404`.
5. ArchUnit architecture tests enforce the package dependency rules and pass.
6. OpenAPI spec at `/v3/api-docs` contains the catalog paths.
7. No forbidden technology (Kafka, Redis, Kubernetes, microservices, CQRS,
   Elasticsearch, event sourcing) has been introduced.
8. Git diff is clean and no unrelated files are modified.
9. The phase review process has been passed before `current-phase.md` is
   updated.
