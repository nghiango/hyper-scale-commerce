# Phase 03 — Event-Driven Architecture

Status: **APPROVED** — ready for implementation.

This plan was produced by inspecting the repository and the documents:

- `AGENTS.md`
- `docs/constitution.md`
- `docs/requirements.md`
- `docs/architecture.md`
- `docs/bootcamp/current-phase.md`
- `docs/bootcamp/phase-02-plan.md`
- `docs/bootcamp/evidence/p2-baseline.md`
- `docs/bootcamp/evidence/p2-profile.md`
- `docs/bootcamp/evidence/p2-tuning.md`
- `docs/bootcamp/evidence/p2-slo-verification.md`
- `docs/adr/0001-technology-stack.md`
- `docs/adr/0002-catalog-bounded-context.md`
- The existing Phase 2 implementation (build, source, tests, evidence)

---

## 1. Phase objective

Introduce reliable asynchronous processing with Kafka and the transactional
outbox pattern, and prove the pattern end-to-end with a real cross-context
event flow: an **Order** bounded context publishes `OrderPlaced`, and an
**Inventory** bounded context consumes it to record reservations idempotently.
This phase makes the constitution's distributed-systems rules (§5) concrete:
durable messages, idempotent consumers, observable failures, bounded retries,
poison-message handling, and explicit eventual consistency.

---

## 2. Why this phase exists

The constitution defines the engineering evolution as:

1. Engineering Foundation
2. Modular Monolith
3. Performance Engineering
4. **Event-Driven Architecture** ← this phase
5. CQRS
6. ...

Phase 2 proved that the Catalog API can be measured and tuned inside the
modular monolith. Phase 3 must introduce the first asynchronous communication
mechanism before later phases (CQRS, service extraction) can exist. The
transactional outbox and Kafka are the smallest durable-messaging foundation
that satisfies the constitution's distributed-systems rules and gives later
phases a proven event backbone.

---

## 3. Starting architecture / state

| Item | State |
|---|---|
| Application | Single Kotlin/Spring Boot `app` module |
| Bounded contexts | Catalog only (`modules.catalog`, read-only REST API) |
| Database | PostgreSQL 16 via Docker Compose; `V1__baseline.sql`, `V2__catalog_product.sql` |
| Data access | `JdbcTemplate` / `NamedParameterJdbcTemplate` |
| API | `GET /catalog/products`, `GET /catalog/products/{id}`, `GET /catalog/products/sku/{sku}`, `GET /catalog/products/{id}/availability` |
| Metrics | Spring Actuator + Micrometer + Prometheus at `/actuator/prometheus` |
| Tuning | Hikari pool, Tomcat threads, HTTP compression, JVM args (see `p2-tuning.md`) |
| Tests | JUnit 5, AssertJ, Testcontainers (PostgreSQL), ArchUnit, spotless, detekt |
| Performance evidence | `p2-baseline.md`, `p2-profile.md`, `p2-tuning.md`, `p2-slo-verification.md` |
| CI | GitHub Actions running `./gradlew build` |
| Docs | ADR-0001 (stack), ADR-0002 (catalog boundaries) |

Phase 2's Definition of Done is assumed complete: `make test` and `make verify`
pass from a clean checkout, the Phase 2 SLOs are verified, and the Phase 2
phase-review has passed before this plan is approved.

---

## 4. Target architecture / state

```text
                     Client
                       |
                       v
                   REST API
                       |
        +--------------+--------------+
        |              |              |
    Catalog         Order         Inventory
        |              |              |
        +--------------+--------------+
                       |
                       v
                  PostgreSQL
                       |
                       v
                   Kafka (events)
        OrderPlaced ──────────────> Inventory
```

The single `app` module and the package-module convention from Phase 1 remain
unchanged. Two new bounded contexts are added as package modules:

```text
com.hyperscale.commerce
  modules
    catalog      # unchanged from Phase 1/2
    order        # domain, application, infrastructure, api
    inventory    # domain, application, infrastructure, api
    shared       # outbox infrastructure shared by publishing contexts
```

Event flow:

```text
POST /orders
  -> order.orders + order.outbox_events (same transaction)
  -> OutboxRelay polls outbox_events
  -> publishes OrderPlaced to Kafka topic order-placed
  -> Inventory consumer (idempotent) records reservation in inventory.reservations
```

---

## 5. Problems this phase addresses

- The platform has no asynchronous processing; every operation is synchronous.
- There is no durable messaging; bounded contexts cannot be decoupled.
- The constitution's distributed-systems rules (§5) are untested.
- Cross-context consistency (e.g., order creation → inventory reservation) has
  no mechanism without direct table access, which the constitution forbids.
- Later phases (CQRS, service extraction) require a proven event backbone.

---

## 6. Architecture changes

- Add two bounded contexts as package modules: `modules.order` and
  `modules.inventory`.
- Add a small shared outbox infrastructure package (`modules.shared`) used only
  by publishing contexts; it must not become a general-purpose library.
- Add Kafka as the event bus between contexts.
- Add the transactional outbox pattern for reliable event publishing.
- Extend ArchUnit rules to enforce the new package boundaries and dependency
  direction.
- Cross-context access happens only through events; no context reads another
  context's tables.

---

## 7. Technology changes

- **Kafka** (new runtime technology, allowed by this phase per the
  constitution's stage 4) via `spring-kafka`.
- **Testcontainers Kafka** (`org.testcontainers:kafka`) for integration tests.
- PostgreSQL remains the source of truth; Kafka is a transport, not a store of
  business state.
- No Redis, no Kubernetes, no microservices, no CQRS, no Elasticsearch, no
  event sourcing. The outbox pattern is a reliable publish mechanism, not event
  sourcing.

---

## 8. Non-functional requirements

- All existing `make test` and `make verify` checks continue to pass.
- All new Kotlin code passes `spotlessCheck` and `detekt`.
- Kafka runs locally via `make up` (Docker Compose) and in tests via
  Testcontainers.
- Messages are durable (Kafka topic with replication for local single-node;
  at-least-once delivery).
- Consumers are idempotent (dedupe by event id).
- Retries are bounded; poison messages are routed to a dead-letter topic.
- No forbidden technology is introduced beyond Kafka.

---

## 9. Performance expectations

Phase 3 does **not** claim the final `< 200ms p95` target for new endpoints.
Local expectations on the existing stack:

- `POST /orders` p95 under **200ms** at 50 concurrent requests per second.
- `GET /orders/{id}` p95 under **100ms** at 100 concurrent requests per second.
- Outbox relay publishes events within **1s** of commit under normal load.
- Inventory consumer processes events with a consumer lag below **100** under
  the Phase 3 test workload.
- Catalog SLOs from Phase 2 must not regress.

---

## 10. Reliability expectations

- Order creation and outbox insert are atomic (same database transaction).
- The outbox relay retries with bounded backoff and never drops events.
- The Inventory consumer is idempotent: duplicate `OrderPlaced` events do not
  double-reserve.
- Poison messages are routed to a dead-letter topic and are observable.
- Kafka health is exposed through Actuator health groups.
- The application still starts only when `readinessState` and `db` are `UP`;
  Kafka unavailability must not prevent startup (lazy/async publish).

---

## 11. Observability requirements

- Existing `/actuator/health`, `/actuator/prometheus`, and `/v3/api-docs`
  continue to work.
- New Micrometer metrics:
  - `events_published_total` (by event type)
  - `events_consumed_total` (by event type, outcome)
  - `events_dlq_total` (by topic)
  - `outbox_relay_lag` (seconds since oldest unpublished event)
  - `kafka_consumer_lag` (via Spring Kafka metrics)
- Event processing failures are logged with structured JSON (non-local
  profiles) without full stack traces for expected errors.

---

## 12. Security considerations

- Kafka runs locally without authentication; no credentials are committed.
- Event payloads contain only the data required by the consumer (order id,
  items with sku/quantity); no PII beyond order data.
- New endpoints (`POST /orders`, `GET /orders/{id}`) remain public in this
  phase; no authentication is introduced.
- Logs must not include full stack traces for `4xx` client errors.

---

## 13. Data considerations

- `order` schema: `orders`, `order_items`, `outbox_events`.
- `inventory` schema: `items` (optional seed), `reservations` (order id, sku,
  quantity, status, event id for dedupe).
- Each bounded context owns its schema; cross-context access happens only via
  events.
- Event payloads are JSON with a `version` field for future schema evolution.
- The outbox table is owned by the publishing context (`order`).

---

## 14. Explicitly out-of-scope capabilities

- CQRS, event sourcing, sagas/orchestration beyond the single `OrderPlaced`
  flow.
- Full Order lifecycle (payment, shipping, cancellation).
- Cart, Customer, Payment, Shipping, Notification contexts.
- Stock math beyond a reservation ledger (no availability checks or
  compensation in this phase).
- Exactly-once delivery (at-least-once + idempotency is the chosen model).
- Redis, Kubernetes, microservices, Elasticsearch, service mesh.
- Multi-node or multi-broker Kafka.

---

## 15. Dependencies on Phase 2

Phase 3 depends on the successful completion of Phase 2, specifically:

- P2-01 through P2-08 are complete and verified.
- `make test` and `make verify` pass from a clean checkout.
- Phase 2 SLOs are verified and evidenced under `docs/bootcamp/evidence/`.
- The Phase 2 phase-review has passed and `docs/bootcamp/current-phase.md` has
  been advanced to Phase 03 before implementation begins.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Kafka adds operational complexity** | Medium | Local single-node Compose service; Testcontainers for tests; no production deployment in this phase. |
| **At-least-once delivery causes duplicate processing** | Medium | Idempotent consumer keyed on event id; integration test proves no double-reservation. |
| **Outbox relay adds publish latency** | Low | Poll interval tuned; `outbox_relay_lag` metric makes it observable. |
| **Two new contexts expand scope** | Medium | Each context implements only the event-relevant operations; no full CRUD. |
| **Testcontainers Kafka is slow in CI** | Low | Single shared Kafka container per test class; documented in the plan. |
| **Pressure to add event sourcing / CQRS prematurely** | Medium | Explicitly deferred; outbox is a publish mechanism, not event sourcing. |

---

## 17. ADRs that may be required

- **ADR-0006 — Kafka as the event broker.** Required: introduces a new runtime
  technology.
- **ADR-0007 — Transactional outbox for reliable publishing.** Required: new
  database architecture and communication mechanism.
- **ADR-0008 — Event schema and versioning strategy (optional).** Required only
  if a contract beyond a JSON `version` field is needed.

---

## 18. Ordered implementation tasks

### P3-01 — ADR: Kafka and transactional outbox

- **Objective:** Record the architectural decision to introduce Kafka and the
  transactional outbox pattern before any code is written.
- **Context:** AGENTS.md requires an ADR for new infrastructure technology and
  new communication mechanisms.
- **Dependencies:** Phase 2 complete and phase-review passed.
- **Scope:** Documentation only.
- **Implementation requirements:**
  - Create ADR-0006 (Kafka as event broker) and ADR-0007 (transactional
    outbox).
  - Document alternatives (PostgreSQL LISTEN/NOTIFY, polling publisher without
    broker, Redis Streams) and why they were rejected.
  - Document the at-least-once + idempotency delivery model.
- **Acceptance criteria:**
  - ADR-0006 and ADR-0007 exist under `docs/adr/` and are accepted.
  - No technology beyond Kafka is introduced.
- **Verification requirements:** Document review; no automated tests.
- **Expected files/components:**
  - `docs/adr/0006-kafka-event-broker.md`
  - `docs/adr/0007-transactional-outbox.md`
- **Architecture impact:** Records the phase's architecture decisions.
- **Out of scope:** Implementation.

### P3-02 — Add Kafka to local infrastructure and tests

- **Objective:** Kafka runs locally via Docker Compose and in tests via
  Testcontainers.
- **Context:** No messaging infrastructure exists yet.
- **Dependencies:** P3-01.
- **Scope:** `compose.yaml`, `app/build.gradle.kts`, `application.yml`, test
  configuration.
- **Implementation requirements:**
  - Add a `kafka` service to `compose.yaml` (single node, KRaft mode).
  - Add `spring-kafka` and `org.testcontainers:kafka` dependencies.
  - Configure `spring.kafka.bootstrap-servers` for local and test profiles.
  - Add a Kafka health indicator to Actuator.
  - Add a smoke integration test that connects to Kafka via Testcontainers.
- **Acceptance criteria:**
  - `make up` starts Kafka.
  - A Testcontainers-based integration test connects to Kafka and passes.
  - `make verify` passes.
- **Verification requirements:** Run the smoke test; run `make verify`.
- **Expected files/components:**
  - `compose.yaml`
  - `app/build.gradle.kts`
  - `app/src/main/resources/application.yml`
  - `app/src/integrationTest/kotlin/.../messaging/KafkaSmokeTest.kt`
- **Architecture impact:** Adds the event transport; no domain changes.
- **Out of scope:** Topics, producers, consumers.

### P3-03 — Transactional outbox infrastructure

- **Objective:** Provide the reusable outbox table, repository, and relay used
  by publishing contexts.
- **Context:** Reliable publishing requires the outbox pattern.
- **Dependencies:** P3-02.
- **Scope:** Shared infrastructure package, one Flyway migration for the
  `order.outbox_events` table.
- **Implementation requirements:**
  - Create `order.outbox_events` (id, aggregate id, event type, payload,
    created_at, published_at).
  - Implement `OutboxRepository` (insert, claim due events, mark published).
  - Implement `OutboxRelay` (scheduled poller that publishes to Kafka and marks
    published).
  - Expose `outbox_relay_lag` metric.
- **Acceptance criteria:**
  - Unit/integration tests cover insert, claim, publish, and mark-published.
  - `make verify` passes.
- **Verification requirements:** Run the outbox tests; run `make verify`.
- **Expected files/components:**
  - `app/src/main/resources/db/migration/V4__order_outbox.sql`
  - `app/src/main/kotlin/.../modules/shared/outbox/...`
  - `app/src/integrationTest/kotlin/.../shared/outbox/OutboxRelayIntegrationTest.kt`
- **Architecture impact:** Adds shared infrastructure used only by publishing
  contexts.
- **Out of scope:** Business events, consumers.

### P3-04 — Order bounded context (publisher)

- **Objective:** `POST /orders` creates an order and writes an `OrderPlaced`
  outbox event atomically.
- **Context:** Order is the first write-capable context and the event source.
- **Dependencies:** P3-03.
- **Scope:** `modules.order` package, migrations for `order.orders` and
  `order.order_items`.
- **Implementation requirements:**
  - Create `order` schema with `orders` and `order_items`.
  - Implement Order domain, `OrderRepository`, `OrderService`, `OrderController`
    (`POST /orders`, `GET /orders/{id}`).
  - Define the `OrderPlaced` event (JSON with `version`, order id, items with
    sku/quantity).
  - Write the order and the outbox event in the same transaction.
- **Acceptance criteria:**
  - Integration test: `POST /orders` returns the order and creates one outbox
    row.
  - `make verify` passes.
- **Verification requirements:** Run the Order integration tests; run
  `make verify`.
- **Expected files/components:**
  - `app/src/main/resources/db/migration/V3__order.sql`
  - `app/src/main/kotlin/.../modules/order/...`
  - `app/src/integrationTest/kotlin/.../modules/order/...`
- **Architecture impact:** Adds the first write-capable bounded context.
- **Out of scope:** Payment, shipping, cancellation, order history.

### P3-05 — Inventory bounded context (consumer)

- **Objective:** Consume `OrderPlaced` and record reservations idempotently.
- **Context:** Inventory is the first event consumer and proves the consumer
  side of the pattern.
- **Dependencies:** P3-04.
- **Scope:** `modules.inventory` package, migration for `inventory.reservations`.
- **Implementation requirements:**
  - Create `inventory` schema with `reservations` (order id, sku, quantity,
    status, event id).
  - Implement a Kafka consumer for topic `order-placed`.
  - Dedupe by event id; duplicate events must not double-reserve.
  - Record reservation state; expose `events_consumed_total` metric.
- **Acceptance criteria:**
  - Integration test: publishing `OrderPlaced` results in one reservation;
    replaying the event does not create a second reservation.
  - `make verify` passes.
- **Verification requirements:** Run the Inventory integration tests; run
  `make verify`.
- **Expected files/components:**
  - `app/src/main/resources/db/migration/V5__inventory.sql`
  - `app/src/main/kotlin/.../modules/inventory/...`
  - `app/src/integrationTest/kotlin/.../modules/inventory/...`
- **Architecture impact:** Adds the first event-consuming bounded context.
- **Out of scope:** Stock availability checks, compensation, inventory CRUD.

### P3-06 — Reliability and observability

- **Objective:** Bounded retries, poison-message handling, and event metrics.
- **Context:** The constitution requires observable failures and bounded
  retries.
- **Dependencies:** P3-05.
- **Scope:** Consumer error handling, dead-letter topic, Micrometer metrics.
- **Implementation requirements:**
  - Configure bounded consumer retries with backoff.
  - Route poison messages to an `order-placed-dlq` topic.
  - Add `events_published_total`, `events_consumed_total`, `events_dlq_total`,
    and `kafka_consumer_lag` metrics.
- **Acceptance criteria:**
  - Failure tests prove: a poison message lands in the DLQ; retries are
    bounded.
  - Metrics are visible at `/actuator/prometheus`.
  - `make verify` passes.
- **Verification requirements:** Run the failure tests; curl the metrics
  endpoint; run `make verify`.
- **Expected files/components:**
  - `app/src/main/resources/application.yml`
  - `app/src/integrationTest/kotlin/.../modules/inventory/InventoryFailureTest.kt`
  - `docs/bootcamp/evidence/p3-reliability.md`
- **Architecture impact:** Operational; no package-dependency change.
- **Out of scope:** Exactly-once delivery, distributed tracing.

### P3-07 — Event-driven integration verification

- **Objective:** Verify the full flow end-to-end and capture evidence.
- **Context:** This is the primary verification gate for the phase.
- **Dependencies:** P3-06.
- **Scope:** End-to-end test and evidence capture.
- **Implementation requirements:**
  - End-to-end test: `POST /orders` → outbox → Kafka → Inventory reservation.
  - Verify idempotency by replaying the event.
  - Save the evidence report under `docs/bootcamp/evidence/p3-event-flow.md`.
- **Acceptance criteria:**
  - The end-to-end test passes.
  - The evidence report documents the flow, timings, and idempotency result.
  - `make verify` passes.
- **Verification requirements:** Run the end-to-end test; review the report.
- **Expected files/components:**
  - `docs/bootcamp/evidence/p3-event-flow.md`
- **Architecture impact:** None; test-only.
- **Out of scope:** Production deployment, multi-node tests.

### P3-08 — Phase 3 final verification

- **Objective:** Confirm the entire phase is complete and ready for phase
  review.
- **Context:** Last task before phase review.
- **Dependencies:** P3-07.
- **Scope:** Run all gates and gather evidence.
- **Implementation requirements:**
  - Run `make clean && make verify` from a fresh checkout.
  - Review git diff for unrelated changes.
  - Confirm all Phase 3 evidence is committed under `docs/bootcamp/evidence/`.
  - Update `docs/bootcamp/current-phase.md` **only if** phase review has already
    passed.
- **Acceptance criteria:**
  - `make verify` passes with all new and existing tests.
  - No unrelated files modified.
  - Phase exit criteria are met.
- **Verification requirements:** Command output, git status, manual checklist.
- **Expected files/components:** None new.
- **Architecture impact:** None.
- **Out of scope:** Phase 3 implementation beyond verification; advancing to
  Phase 4.

### Dependency graph

```text
P3-01 ──> P3-02 ──> P3-03 ──> P3-04 ──> P3-05 ──> P3-06 ──> P3-07 ──> P3-08
```

### Suggested execution order

P3-01 → P3-02 → P3-03 → P3-04 → P3-05 → P3-06 → P3-07 → P3-08

---

## 19. Phase exit criteria

Phase 3 is complete only when all of the following are true:

1. All tasks P3-01 through P3-08 are implemented and verified.
2. `make verify` passes from a clean checkout with no manual intervention beyond
   JDK 21, Docker, and Make.
3. The CI pipeline is green for the changes.
4. Kafka runs via Docker Compose and Testcontainers.
5. `POST /orders` writes the order and an outbox event atomically.
6. The `OrderPlaced` event is published by the outbox relay and consumed by the
   Inventory context.
7. The Inventory consumer is idempotent (duplicate events do not
   double-reserve).
8. Retries are bounded and poison messages land in a dead-letter topic.
9. Event metrics are visible at `/actuator/prometheus`.
10. The event flow is evidenced under `docs/bootcamp/evidence/p3-event-flow.md`.
11. No forbidden technology beyond Kafka (Redis, Kubernetes, microservices,
    CQRS, Elasticsearch, event sourcing) has been introduced.
12. Git diff is clean and no unrelated files are modified.
13. The phase review process has been passed before `current-phase.md` is
    updated to Phase 04.
