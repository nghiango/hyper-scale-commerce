# ADR-0012: Resilience Strategy for the Two-Service Platform

- Status: Accepted
- Date: 2026-08-14
- Phase: 6 — Resilience Engineering

## Context

`docs/constitution.md` defines the engineering evolution and stage 7 is
Resilience Engineering. Phase 5 (ADR-0010, ADR-0011) extracted the Order query
side into the `order-query` service. The platform now has two deployables
(`app` and `order-query`) communicating exclusively through Kafka
(`order-placed`) and sharing one PostgreSQL instance with per-service schemas.

The constitution's distributed-systems rules (§5) require that whenever
asynchronous processing is introduced:

- messages must be durable,
- consumers must be idempotent,
- failures must be observable,
- retries must be bounded,
- poison messages must be handled,
- eventual consistency must be explicit.

Phase 3-5 already implemented these mechanisms:

- **Transactional outbox** (ADR-0007) makes publishes durable in the monolith.
- **Kafka** (ADR-0006) makes the event log durable and replayable.
- **At-least-once delivery** with idempotent consumers (dedupe by event id).
- **Bounded retries** and a **DLQ** (`order-placed-dlq`) for poison messages.
- **Per-service data ownership** (ADR-0011) isolates failure blast radius.

What is missing is **evidence that these mechanisms actually protect the
system when dependencies fail**. Phase 6 must design and execute failure
experiments, document the results, and close any gaps. It must also decide
which resilience mechanisms belong in this phase and which are deferred.

## Alternatives Considered

1. **Testcontainers lifecycle control for failure-injection (chosen).**
   Use Testcontainers' `stop()` and `start()` to take PostgreSQL and Kafka down
   and bring them back, and use polling helpers to wait for recovery. This is
   the smallest test-only extension of the existing test infrastructure, adds
   no runtime technology, and is sufficient for the outage scenarios targeted
   this phase (broker down, database down, partial outages, poison messages).

2. **Toxiproxy for network partitions and latency injection.** A
   test-oriented network proxy would let us simulate partitions and slow
   connections more realistically. Rejected for this phase: it is a new
   technology and the out-of-scope list for Phase 6 explicitly defers network
   partitions to Phase 10 (Chaos Engineering). Container stop/start is enough
   for dependency-outage experiments.

3. **Circuit breakers (e.g., Resilience4j) across deployables.** Circuit
   breakers protect against cascading failures but require new runtime
   libraries and meaningful fallback logic. The current use case has no
   synchronous inter-service calls (REST/gRPC) where circuit breakers are
   most valuable, and the event-driven flows already degrade gracefully
   (buffer in outbox, serve stale read model). Rejected for this phase;
   revisit in Resilience Engineering once more sync flows exist.

4. **Chaos engineering platform (e.g., Chaos Monkey, Litmus).** Chaos tooling
   is explicitly Phase 10. It is overkill for the deterministic, bounded
   failure experiments in this phase and would introduce forbidden tooling.
   Rejected.

## Decision

Adopt the following resilience strategy for Phase 6:

- **Keep the runtime stack unchanged.** No new runtime libraries, brokers, or
  infrastructure are introduced. Resilience is delivered by the existing
  patterns: transactional outbox, durable Kafka log, at-least-once delivery,
  idempotent consumers, bounded retries, DLQ, and per-service data ownership.
- **Failure-injection by Testcontainers lifecycle control.** Use shared
  Testcontainers' `stop()` and `start()` to simulate PostgreSQL and Kafka
  outages in integration tests. Add wait-for-recovery helpers that poll with
  deadlines and assert the system catches up.
- **Experiment matrix:**
  - **Kafka outage:** broker down at startup and during operation; prove
    buffering, recovery, and catch-up without data loss.
  - **PostgreSQL outage:** database down for `app` and `order-query`; prove
    no committed data is lost and both services recover when PostgreSQL
    returns.
  - **Consumer resilience:** poison message for `order-query` projection;
    prove bounded retries and DLQ routing, plus continued processing of
    subsequent valid messages.
  - **Partial outage:** each service down while the other runs; prove no data
    loss and catch-up on restart.
- **Defer to later phases:** network-partition proxies, circuit breakers,
  bulkheads, backpressure, rate limiting, load shedding, end-to-end
  distributed tracing and operational observability (Phase 8), concurrency
  control and distributed locks (introduced only when a use case requires
  them), chaos engineering, and any runtime resilience libraries.
- **Evidence:** all experiments and recovery procedures are captured in
  `docs/bootcamp/evidence/p6-resilience.md`.

## Operational Cost

- Testcontainers restart time for PostgreSQL and Kafka in the `app` and
  `order-query` integration tests. Restarted container state is reset (no
  persistent data) but the outbox and Kafka topic replay mechanism still
  exercise the recovery paths under test.
- A small amount of shared test code to stop/start containers and poll for
  recovery.
- Manual review of failure-experiment evidence before phase review.

## Failure Modes

- **Kafka unavailable:** `app` writes to the outbox and retries publishing
  when the broker returns; `order-query` starts healthy with an empty/stale
  read model and catches up when the broker returns; no events are lost.
- **PostgreSQL unavailable:** the affected service reports degraded health and
  stops accepting writes or fresh reads; committed data remains in the
  database and is available after restart; the outbox relay resumes; the
  projection catches up.
- **Poison message:** the `order-query` projection consumer retries a bounded
  number of times and then routes the message to `order-placed-dlq`;
  subsequent messages are still processed.
- **Partial outage:** the surviving service continues to serve; the down
  service catches up via durable events on restart.
- **Flaky or slow tests:** mitigated by polling with deadlines and keeping
  experiments focused on single failure modes.

## Consequences

- P6-02 implements the failure-injection test harness.
- P6-03 through P6-06 execute the outage and consumer-resilience experiments.
- P6-07 captures the evidence and closes the phase.
- No new runtime technology is introduced, preserving the Phase 5
  architecture.
- The full set of distributed-systems and resilience principles is now
  captured in `docs/constitution.md`; this phase proves the existing
  event-driven mechanisms and defers advanced patterns to later phases.
- Circuit breakers, bulkheads, backpressure, rate limiting, distributed
  tracing, network-partition tools, and chaos engineering remain explicit
  future-phase concerns.
