# ADR-0009: Spring Data JDBC + jOOQ for Data Access

- Status: Accepted
- Date: 2026-08-13
- Phase: 4 — CQRS

## Context

Data access is currently hand-written `JdbcTemplate` /
`NamedParameterJdbcTemplate` with row mappers across catalog, order, inventory,
and the shared outbox. The SQL is untyped and duplicated across contexts.
Phase 4 introduces a read model with JSONB access and pagination, where
type-safe queries reduce error risk, and the platform will grow (more contexts,
service extraction), so a consistent data-access pattern is needed before the
surface expands further.

## Alternatives Considered

1. **Spring Data JDBC + jOOQ (chosen)** — Spring Data JDBC for aggregate
   persistence (Order, Product, Reservation); jOOQ for type-safe queries
   (search, pagination, the order read model, the outbox claim). Domain
   repository interfaces remain the boundary.
2. **Stay on `JdbcTemplate`** — works and is verified, but untyped SQL and
   hand-written mappers scale poorly as the query surface grows.
3. **Spring Data JPA** — ORM with lazy loading, caching, and entity lifecycle
   complexity; heavier than needed for a JDBC-centric platform.
4. **MyBatis** — SQL mapping framework; less type safety than jOOQ and adds a
   second mapping layer.
5. **jOOQ only** — type-safe SQL everywhere, but no aggregate persistence
   support; aggregates would be assembled manually.
6. **Spring Data JDBC only** — aggregate persistence, but complex queries
   (JSONB, search, pagination) become awkward in the repository abstraction.

## Decision

Adopt **Spring Data JDBC + jOOQ** as the data-access stack:

- **Spring Data JDBC** (`spring-boot-starter-data-jdbc`) for aggregate
  persistence: Order with items, Product, Reservation.
- **jOOQ** for type-safe queries: catalog search/pagination/availability, the
  order read model, and the outbox claim.
- jOOQ sources are generated from the Flyway migration DDL at build time
  (DDL-based codegen, no database connection required) and wired into the
  Kotlin source set, keeping `make verify` and CI database-free at build time.
- Full migration: all `JdbcTemplate` repository implementations are replaced
  (catalog, order, inventory, shared outbox); domain repository interfaces are
  unchanged.
- Catalog SLOs from Phase 2 are re-verified after the migration and must not
  regress.

Rationale: the pair covers both needs — aggregates via Spring Data JDBC and
complex/type-safe queries via jOOQ — without an ORM, and the migration happens
while the codebase is still small enough to absorb it safely.

## Operational Cost

- Two data-access libraries and the jOOQ codegen build step.
- Context-by-context migration effort with full test suites per context.
- Regression risk on verified Phase 2/3 code, mitigated by the catalog SLO
  gate and `make verify`.

## Failure Modes

- **jOOQ codegen dialect parsing:** DDL-based codegen may struggle with
  PostgreSQL-specific syntax; fallback is checked-in generated sources.
- **Migration regressions:** mitigated by context-by-context migration, the
  full test suite per context, and the catalog SLO re-verification.
- **Two-library overlap:** mitigated by a clear division of labor (aggregates
  vs. queries) enforced in review.

## Consequences

- P4-02 establishes the build foundation and migrates the shared outbox.
- P4-03, P4-04, and P4-05 migrate catalog, order, and inventory.
- P4-06 and P4-07 use jOOQ for the read model and the query API.
- No `JdbcTemplate` remains in repository implementations (phase exit
  criterion).
