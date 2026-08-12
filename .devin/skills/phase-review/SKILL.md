---
name: phase-review
description: Review whether a HyperScale Commerce BootCamp phase has been correctly completed. Use this skill to verify implementation, architecture, requirements, tests, documentation, performance evidence, failure analysis, and phase exit criteria before declaring a phase complete.
---

# Phase Review Skill

## Purpose

Use this skill to determine whether a HyperScale Commerce BootCamp phase is
complete and ready to progress to the next phase.

The review must verify both:

- what was implemented
- whether the implementation satisfies the phase's engineering objectives

A phase MUST NOT be considered complete merely because:

- the application builds
- tests pass
- tasks are marked complete
- the implementation works locally

The review must evaluate the complete phase against its documented
requirements, architectural constraints, acceptance criteria, and evidence.

This skill is a **review and verification procedure**.

It MUST NOT modify application implementation unless the user explicitly asks
for remediation.

---

# 1. Required Context

Before starting:

1. Read `AGENTS.md`.
2. Read `docs/constitution.md`.
3. Read `docs/architecture.md`.
4. Read `docs/bootcamp/current-phase.md`.
5. Read the complete phase specification.
6. Read the phase implementation plan.
7. Read all tasks belonging to the phase.
8. Read relevant ADRs in `docs/adr/`.
9. Inspect the implementation.
10. Inspect relevant tests.
11. Inspect relevant evidence under `docs/bootcamp/evidence/`.

Determine:

- phase objectives
- required tasks
- acceptance criteria
- architectural constraints
- required technologies
- forbidden future-phase technologies
- quality requirements
- performance requirements
- reliability requirements
- documentation requirements
- phase exit criteria

Do not infer completion from task status alone.

---

# 2. Review Scope

Define the review scope before starting:

```text
Phase:
Phase Objective:
Phase Requirements:
Implementation Plan:
Tasks:
Architecture:
Tests:
Performance Evidence:
Failure Evidence:
Documentation:
Exit Criteria: