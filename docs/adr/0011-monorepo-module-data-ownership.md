# ADR-0011: Monorepo Module and Per-Service Data-Ownership Model

- Status: Accepted
- Date: 2026-08-14
- Phase: 5 — Service Extraction

## Context

ADR-0010 extracts the Order query side as the first independent deployable.
That decision requires supporting choices about how one repository produces
two deployables and how each deployable owns its data:

- The build is a single Gradle module; nothing enforces at compile time what
  may be shared between deployables.
- The `OrderPlaced` event contract lives inside the monolith's Order module;
  two deployables consuming it need a shared, versioned location.
- The read model currently lives in the monolith-owned `order` schema
  (`order.order_read_model`), which violates the constitution's
  data-ownership rule once the query side is a separate service.
- Migrations (Flyway) and jOOQ codegen are configured once for the monolith;
  each deployable needs its own.
- Both services need container images and Compose wiring without changing the
  default `make up` developer workflow (infrastructure only).

## Alternatives Considered

1. **Monorepo with Gradle modules `app` + `order-query` + `contracts`
   (chosen)** — one repository, three modules; `contracts` holds the event
   contract; compile-time module boundaries enforce sharing. Consistent with
   evolutionary architecture and keeps CI (`./gradlew build --no-daemon`)
   unchanged in shape.
2. **Separate repositories per service** — stronger isolation, but requires
   publishing/versioning the contract artifact, duplicates build tooling, and
   is disproportionate for the first extraction; can be revisited when the
   number of services justifies it.
3. **Copy the event contract into both deployables** — no shared module, but
   the two copies drift silently; a shared `contracts` module makes the
   dependency explicit and compile-checked.
4. **Share the `order` schema between deployables** — keep
   `order.order_read_model` and let `order-query` read from the monolith's
   schema. Rejected: it couples the service to the monolith's schema and
   migrations and violates per-service data ownership.
5. **Separate physical databases per service** — forbidden until a later
   phase; schema-level separation inside the existing PostgreSQL instance
   provides the ownership boundary without new infrastructure.

## Decision

- **Module split.** The build becomes three Gradle modules: `app` (monolith:
  Catalog, Order command, Inventory, shared outbox), `order-query` (extracted
  service), and `contracts` (event contracts). `app` and `order-query` may
  depend on `contracts`; they must not depend on each other. Shared build
  conventions are factored without introducing new plugins.
- **Contracts module.** `OrderPlacedEvent` (and `OrderPlacedItem`) move to
  `contracts`. The event is a published contract: changes must remain
  additive and the `version` field is retained.
- **Per-service migrations and codegen.** Each deployable owns its Flyway
  migration directory and its jOOQ codegen input, preserving the existing
  database-free (DDL-based) build-time codegen pattern.
- **Schema ownership.** The read model moves from `order.order_read_model` to
  a new `order_query.order_read_model`, owned solely by the `order-query`
  service. The monolith's migration set drops the read model; the `order`
  schema remains monolith-owned. One PostgreSQL instance; schemas are the
  ownership boundary.
- **Container model.** Each service gets a JRE-based Dockerfile
  (eclipse-temurin 21-jre) packaging its bootJar. Compose gains both services
  behind a `services` profile, so `make up` still starts only infrastructure
  by default. Ports: `app` 8080, `order-query` 8081.

Rationale: the monorepo module split enforces sharing rules at compile time
with minimal tooling change, the `contracts` module makes the event contract
a first-class published artifact, and schema-level ownership satisfies the
constitution's data-ownership rule without introducing forbidden
infrastructure.

## Operational Cost

- Three build modules with per-module Flyway/jOOQ configuration.
- Two Dockerfiles and Compose service entries to maintain.
- The `order_query` schema must be created before `order-query` migrates
  (handled by the monolith's infrastructure provisioning or a dedicated
  migration step).
- Developers running only `make up` see no change; running the full two
  service topology requires the `services` Compose profile.

## Failure Modes

- **Contract drift:** mitigated by the shared `contracts` module — a
  producer-side change that breaks consumers fails compilation of the
  dependent module. Wire-level drift (rolling deploys) is mitigated by the
  additive-change rule.
- **Migration split-brain:** each service migrates only its own schema;
  Flyway's per-module history tables prevent cross-service interference.
- **Codegen divergence:** per-module jOOQ codegen inputs are derived from the
  module's own Flyway DDL, so a service cannot generate classes for tables it
  does not own.
- **Startup order:** both services tolerate missing dependencies at startup
  (Kafka retry/backoff; read model starts empty) — verified in P5-07.

## Consequences

- P5-02 implements the module split and `contracts` extraction with zero
  behavior change.
- P5-03 creates the `order-query` skeleton with its own Flyway/jOOQ/Kafka
  configuration; P5-04 moves the query side and its migrations to the
  `order_query` schema; P5-06 adds the container images and Compose wiring.
- The module-boundary rule (`app` and `order-query` must not depend on each
  other) is enforced by Gradle; ArchUnit continues to enforce package rules
  within each module.
- A future move to separate physical databases per service would only change
  connection configuration, not ownership boundaries.
