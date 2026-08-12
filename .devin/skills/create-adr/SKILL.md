---
name: create-adr
description: Create an Architecture Decision Record for a significant HyperScale Commerce architectural decision, documenting context, alternatives, decision, consequences, failure modes, and operational impact.
---

# Create Architecture Decision Record

## Purpose

Create an Architecture Decision Record (ADR) for a significant architectural
decision in HyperScale Commerce.

An ADR records:

- why a decision was necessary
- what alternatives were considered
- what was chosen
- why it was chosen
- what consequences the decision introduces
- how the decision can be operated, tested, and reversed

An ADR is a record of an architectural decision.

It is NOT a substitute for architectural review or approval.

---

# 1. When to Use This Skill

Use this skill when a change introduces or significantly modifies:

- architecture
- bounded-context boundaries
- communication patterns
- persistence architecture
- consistency models
- messaging
- caching architecture
- service boundaries
- deployment architecture
- resilience mechanisms
- scalability mechanisms
- infrastructure technology
- security architecture
- observability architecture

Examples:

- introducing Kafka
- introducing Redis
- extracting a microservice
- introducing CQRS
- introducing a read model
- changing database ownership
- introducing an event-driven workflow
- introducing a circuit breaker strategy
- changing the consistency model
- introducing Kubernetes
- introducing a service mesh

---

# 2. When NOT to Use This Skill

Do NOT create an ADR for ordinary implementation details.

Examples:

- renaming a variable
- adding a unit test
- fixing a typo
- adding a simple endpoint
- changing a private method
- refactoring without architectural impact
- changing formatting
- adding a trivial helper
- fixing a bug without changing architecture

When uncertain, determine whether the decision changes how major components
interact, scale, persist data, communicate, or operate.

If it does not, an ADR is probably unnecessary.

---

# 3. Required Context

Before creating an ADR, read:

1. `AGENTS.md`
2. `docs/constitution.md`
3. `docs/architecture.md`
4. `docs/bootcamp/current-phase.md`
5. current phase specification
6. relevant BootCamp task
7. relevant existing ADRs

Check whether an existing ADR already covers the decision.

Do not create duplicate ADRs.

---

# 4. Decision Status

New ADRs MUST initially use:

```text
Status: Proposed