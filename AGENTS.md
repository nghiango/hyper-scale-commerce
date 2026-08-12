# HyperScale Commerce — AI Engineering Instructions

## Mission

You are an implementation agent working on HyperScale Commerce.

The goal is to evolve a deliberately simple commerce application into a
cloud-native distributed platform capable of:

- 10,000+ concurrent users
- sub-200ms p95 latency for defined critical APIs
- 5x traffic spikes
- 99.9% availability
- zero intentional data loss
- horizontally scalable workloads

You are NOT responsible for independently redesigning the architecture.

The architecture is evolved incrementally according to the BootCamp phases.

---

# 1. Read Before Coding

Before modifying code, read:

1. docs/constitution.md
2. docs/requirements.md
3. docs/architecture.md
4. the current BootCamp phase
5. relevant ADRs

Do not begin implementation until you understand the current constraints.

---

# 2. Core Principles

## Correctness before performance

Do not optimize code without evidence.

## Measure before changing architecture

Performance claims must be supported by measurements.

## Simple before distributed

Do not introduce distributed infrastructure unless the current phase requires it.

## PostgreSQL is the source of truth

Do not introduce another system as the authoritative source of business data
without an explicit ADR.

## Explicit failure semantics

Every distributed operation must define:

- timeout behavior
- retry behavior
- idempotency
- failure recovery
- observability

## Architecture must be enforceable

Do not rely only on documentation.

Important architectural rules should be tested automatically.

---

# 3. Technology Introduction Rules

Do NOT introduce the following unless the current phase explicitly allows it:

- Kafka
- Redis
- Kubernetes
- Elasticsearch
- microservices
- CQRS
- service mesh
- distributed transactions
- event sourcing

When a technology becomes necessary, document:

1. the problem
2. alternatives considered
3. why the technology solves the problem
4. operational cost
5. failure modes

Create an ADR when required.

---

# 4. Coding Rules

Prefer:

- small modules
- explicit dependencies
- immutable data
- clear domain boundaries
- dependency inversion
- testable components

Avoid:

- speculative abstractions
- generic frameworks
- unnecessary design patterns
- premature optimization
- hidden global state
- cross-domain database access

---

# 5. Testing

Every feature must have appropriate tests.

Minimum expectations:

- unit tests for business logic
- integration tests for persistence
- API tests for externally visible behavior

When distributed behavior exists, add:

- contract tests
- failure tests
- idempotency tests

---

# 6. Observability

Production-relevant operations should provide:

- structured logs
- metrics
- distributed tracing where applicable

Never log:

- passwords
- access tokens
- API keys
- secrets
- sensitive customer information

---

# 7. Performance

Never claim that a performance target has been achieved without measurement.

Performance reports should contain:

- workload
- concurrency
- request rate
- duration
- p50
- p95
- p99
- error rate
- CPU
- memory
- relevant database metrics

---

# 8. Scope Discipline

Only modify files necessary for the assigned task.

Do not refactor unrelated code.

Do not upgrade dependencies unless required.

Do not change architecture without documenting the reason.

---

# 9. Verification

Before completing a task:

1. run tests
2. run static analysis
3. run formatting
4. verify migrations
5. verify application startup
6. inspect the final diff

Report:

- files changed
- tests executed
- results
- architectural impact
- risks
- remaining work

---

# 10. When Requirements Are Ambiguous

Do not silently invent architectural requirements.

Prefer the smallest implementation consistent with:

- current phase
- architecture
- constitution
- acceptance criteria

If ambiguity materially affects architecture, stop and explain the ambiguity.

---

# 11. Definition of Done

A task is complete only when:

- implementation is complete
- tests pass
- relevant documentation is updated
- architectural rules remain satisfied
- no unnecessary dependencies were introduced
- verification evidence exists