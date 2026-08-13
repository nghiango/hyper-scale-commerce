# ADR-0002: Catalog Bounded-Context Boundaries and Data Ownership

- Status: Accepted
- Date: 2026-08-13
- Phase: 1 — Modular Monolith

## Context

`docs/constitution.md` and `docs/architecture.md` prescribe a modular-monolith
architecture with bounded-context isolation. Phase 0 built the engineering
foundation; Phase 1 introduces the first business bounded context, Catalog.

Before any catalog code is written, three decisions must be made explicit:

1. **Physical module strategy** — package modules inside one Gradle `app` module
   or Gradle subprojects.
2. **Data ownership** — whether the Catalog context uses a dedicated PostgreSQL
   schema and how it is named.
3. **Data-access style** — whether to keep `JdbcTemplate` or introduce a higher
   level data-access framework.

These decisions affect every later context (Customer, Cart, Order, etc.) and
must be recorded so that subsequent phases follow a single convention.

## Alternatives Considered

### 1. Package modules vs. Gradle subprojects

- **Package modules** (chosen): isolate bounded contexts by package under a
  single `app` module. Boundaries are enforced by ArchUnit tests. Low churn,
  fast builds, no multi-module Gradle configuration, and still a true modular
  monolith.
- **Gradle subprojects**: one subproject per bounded context. Stronger
  compilation boundaries, but requires `settings.gradle.kts` and dependency
  management changes. Overkill for the first business context.

### 2. PostgreSQL schema per context vs. `public` schema

- **Dedicated `catalog` schema** (chosen): makes data ownership explicit and
  prevents accidental cross-context table access. Aligns with the constitution
  rule that each bounded context owns its persistence model.
- **Stay in `public` schema**: simpler, but makes it easy for future contexts to
  query Catalog tables directly. Deferring the schema split increases the cost
  of enforcement later.

### 3. Data-access style

- **Keep `JdbcTemplate`** (chosen): already part of the accepted stack
  (ADR-0001). No new dependency, no new runtime, and no object-relational
  mapping. Keeps the first phase small and explicit.
- **Spring Data JDBC**: slightly less boilerplate, but is not in ADR-0001 and
  requires a new dependency and a new mental model.
- **Spring Data JPA**: not in ADR-0001; introduces entities, sessions,
  lazy-loading, and a larger runtime surface. Deferred.

## Decision

Adopt the following for Phase 1 and all future bounded contexts until a phase
plan explicitly changes it:

- **Package root:** `com.hyperscale.commerce.modules.catalog`
- **Package layers:**
  - `modules.catalog.domain` — entities, value objects, repository interfaces
  - `modules.catalog.application` — services, DTOs, use cases
  - `modules.catalog.infrastructure` — repository implementations, row mappers
  - `modules.catalog.api` — REST controllers
- **Dependency direction:**
  ```text
  catalog.api -> catalog.application -> catalog.domain
  catalog.infrastructure -> catalog.domain (implements interfaces)
  ```
- **Database schema:** a dedicated PostgreSQL schema `catalog` for the Catalog
  context. The first migration will be `V2__catalog_product.sql` and will create
  `catalog.products`.
- **Data-access style:** `JdbcTemplate` and parameterized SQL.
- **Boundary enforcement:** ArchUnit tests will guard the package dependency
  rules and will fail the build on any violation.

## Operational Cost

- Developers must place new code in the correct package. ArchUnit makes mistakes
  fast-feedback.
- `catalog` schema must be created before tables in every Catalog migration.
- `JdbcTemplate` is more verbose than JPA; acceptable because Catalog in Phase 1
  has only read operations and a small surface.

## Failure Modes

- **Package boundaries drift:** mitigated by ArchUnit tests that run in `check`.
- **Cross-context table access:** mitigated by PostgreSQL schema convention and
  by architecture tests that forbid imports of `catalog.infrastructure` from
  other contexts.
- **JdbcTemplate verbosity becomes painful:** mitigated by keeping the first
  context small; a later ADR can revisit data access if measurements justify it.

## Consequences

- P1-02 will create the package structure.
- P1-03 will create the `catalog` schema and `products` table via Flyway.
- P1-04 and P1-05 will implement the domain and repository under the chosen
  packages.
- P1-02 will add ArchUnit to enforce the dependency rules documented here.
- No new runtime technology is introduced; the stack remains as defined in
  ADR-0001.
