# ADR-0001: Technology Stack

- Status: Accepted
- Date: 2026-08-12
- Phase: 0 — Engineering Foundation

## Context

The engineering documentation (`AGENTS.md`, `docs/constitution.md`,
`docs/architecture.md`, `docs/bootcamp/phase-00.md`) defines architectural
constraints and Phase 0 acceptance criteria but does not specify a technology
stack. A stack must be chosen before any Phase 0 task can be implemented,
because it determines the build system, application skeleton, migration
tooling, testing infrastructure, and CI pipeline.

Phase 0 requires: application skeleton, PostgreSQL via Docker Compose,
configuration management, database migrations, health checks, structured
logging, metrics, OpenAPI, unit and integration testing, static analysis,
formatting verification, and a CI pipeline.

## Alternatives Considered

1. **Java / Spring Boot** — Spring Boot 3.x, Gradle, Flyway, JUnit 5,
   Testcontainers, springdoc-openapi, Spotless/Checkstyle.
2. **TypeScript / Node (NestJS)** — fast iteration, strong OpenAPI support;
   weaker compile-time guarantees, larger operational variance at scale.
3. **Go** — small binaries, explicit code; OpenAPI and migration tooling are
   less integrated, more boilerplate for validation/config.
4. **Python / FastAPI** — fastest to write; weakest typing, GIL constraints
   work against the concurrency targets in the constitution.

## Decision

Adopt **Java 21 with Spring Boot 3.x**, with:

- Gradle (Kotlin DSL) with the wrapper committed to the repository
- Flyway for database migrations; PostgreSQL 16 via Docker Compose
- JUnit 5 + AssertJ for unit tests; Testcontainers for integration tests
- Spring Actuator + Micrometer (Prometheus) for health and metrics
- springdoc-openapi for OpenAPI generation
- Spotless (google-java-format) and Checkstyle for formatting and static
  analysis
- GitHub Actions for CI
- Make as the local task runner

Rationale: Spring Boot satisfies every Phase 0 acceptance criterion with
mature, first-party integrations (actuator health groups, Flyway auto-run,
Micrometer, springdoc). Java 21 LTS provides long support lifetime and the
strongest ecosystem for the later BootCamp phases (performance engineering,
event-driven architecture, service extraction) without locking the project
into any of those technologies now.

## Operational Cost

- JVM memory footprint and startup time are higher than Go/Python; acceptable
  for a long-running service, mitigated in later phases if measurements
  justify it.
- Gradle wrapper must be committed and kept consistent across machines.
- Testcontainers requires Docker locally and in CI.
- Team must maintain Spring Boot version upgrades deliberately (no automatic
  upgrades, per AGENTS.md scope discipline).

## Failure Modes

- Stack choice proves wrong for a later phase: mitigated by modular monolith
  boundaries (constitution §3) keeping business logic isolated from
  framework concerns, and by ADRs for any future infrastructure change.
- Version drift between developer machines: mitigated by the committed
  Gradle wrapper and a pinned Java toolchain.
- CI platform assumption (GitHub Actions) changes: isolated to a single
  workflow file; portable because CI invokes `make` targets.

## Consequences

- All Phase 0 tasks (P0-02 through P0-14 in
  `docs/bootcamp/phase-00-plan.md`) are implemented against this stack.
- No technology outside this list may be introduced without a new ADR.
