---
name: verify-task
description: Verify that a HyperScale Commerce BootCamp task has been correctly implemented according to its requirements and acceptance criteria. Use this skill after implementation to validate scope, correctness, tests, architecture, documentation, and unintended changes without modifying the implementation.
---

# Verify Task Skill

## Purpose

Use this skill to determine whether a single BootCamp task has been correctly
implemented and is ready to be considered complete.

The verification must confirm:

- task requirements are satisfied
- acceptance criteria are satisfied
- implementation is correct
- relevant tests pass
- implementation stays within task scope
- architecture remains compliant
- future-phase technologies are not introduced
- documentation is updated where required
- unrelated files are not modified
- no obvious regressions were introduced

This skill is a **verification procedure**.

It MUST NOT modify application implementation unless the user explicitly asks
for remediation.

---

# 1. Required Context

Before starting:

1. Read `AGENTS.md`.
2. Read `docs/constitution.md`.
3. Read `docs/architecture.md`.
4. Read `docs/bootcamp/current-phase.md`.
5. Read the relevant phase requirements.
6. Read the phase implementation plan.
7. Read the complete task specification.
8. Read relevant ADRs in `docs/adr/`.
9. Inspect the implementation.
10. Inspect relevant tests.
11. Inspect the complete git diff.

Determine:

- task objective
- task scope
- acceptance criteria
- technical constraints
- architectural constraints
- required technologies
- forbidden technologies
- expected files
- expected behavior
- verification requirements

Do not infer task completion from the existence of code alone.

---

# 2. Scope

Define the verification scope:

```text
Phase:
Task:
Task Objective:
Requirements:
Acceptance Criteria:
Expected Files:
Architectural Constraints:
Verification Commands: