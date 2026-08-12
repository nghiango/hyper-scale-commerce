---
name: architecture-review
description: Review HyperScale Commerce implementation against architectural rules, bounded-context boundaries, dependency direction, technology constraints, data ownership, and architecture decisions. Produces evidence and a PASS/FAIL decision without modifying implementation.
---

# Architecture Review

## Purpose

Perform an independent architecture review of a completed or proposed change.

The objective is to determine whether the implementation complies with the
architecture defined by the HyperScale Commerce BootCamp.

This skill is a **review and verification procedure**.

It MUST NOT redesign or modify the implementation unless the user explicitly
requests remediation.

---

# 1. Review Principles

Follow these principles:

1. Review the architecture that is actually implemented.
2. Compare implementation against documented architecture.
3. Do not approve architecture based only on documentation.
4. Prefer automated evidence over subjective judgment.
5. Identify architecture drift.
6. Identify hidden coupling.
7. Identify violations of bounded-context ownership.
8. Identify unnecessary complexity.
9. Do not introduce future-phase technologies.
10. Do not fix findings during the review.

A finding is not automatically a failure.

Classify findings as:

- BLOCKER
- HIGH
- MEDIUM
- LOW
- OBSERVATION

---

# 2. Required Context

Before starting the review, read:

1. `AGENTS.md`
2. `docs/constitution.md`
3. `docs/architecture.md`
4. `docs/bootcamp/current-phase.md`
5. the current phase specification
6. the current phase implementation plan
7. the task being reviewed
8. relevant ADRs under `docs/adr/`

If any required document is missing, report it as a review finding.

Do not invent architectural rules that are not supported by the repository
documentation.

---

# 3. Identify Review Scope

Determine:

- task ID
- task title
- changed files
- affected modules
- affected bounded contexts
- affected infrastructure
- architectural decisions involved

Inspect:

```bash
git status --short
git diff --stat
git diff