---
name: create-phase-plan
description: Use when the current BootCamp phase is complete and the next phase plan must be created from the actual repository state, existing architecture, and approved constraints.
---

# Create the Next BootCamp Phase Plan

## Overview

Design the next engineering phase for the HyperScale Commerce platform by
inspecting the actual repository state and building the smallest necessary
evolution from the current phase.

## When to Use

- The current BootCamp phase has completed its verification and review.
- The next phase must be planned before implementation begins.
- The plan must be justified by the existing architecture, constitution,
  requirements, and ADRs.

## Process

Create the next phase BootCamp plan for HyperScale Commerce.

The previous phase is complete.

### Before planning

1. Read `AGENTS.md`.
2. Read `docs/constitution.md`.
3. Read `docs/requirements.md`.
4. Read `docs/architecture.md`.
5. Read `docs/bootcamp/current-phase.md`.
6. Read the complete current phase specification.
7. Read the current phase implementation plan.
8. Read the current phase verification evidence.
9. Read all relevant ADRs.
10. Inspect the current repository architecture and implementation.

### Goal

Design the next phase as the next evolutionary step of the HyperScale Commerce
platform.

### Requirements

- Build on the actual state of the current phase implementation.
- Do not redesign the current phase.
- Do not introduce technologies that belong to later phases.
- Do not invent requirements that are not justified by the BootCamp goals.
- Preserve the architectural principles defined in `AGENTS.md` and the
  constitution.
- Identify what the next phase must accomplish before defining tasks.
- Make the phase incremental and implementable.
- Prefer the smallest architectural evolution that achieves the phase goals.

### Output

Create:

```
docs/bootcamp/phase-NN-plan.md
```

The plan must contain:

1. Phase objective
2. Why this phase exists
3. Starting architecture/state
4. Target architecture/state
5. Problems this phase addresses
6. Architecture changes
7. Technology changes
8. Non-functional requirements
9. Performance expectations
10. Reliability expectations
11. Observability requirements
12. Security considerations
13. Data considerations
14. Explicitly out-of-scope capabilities
15. Dependencies on the previous phase
16. Risks
17. ADRs that may be required
18. Ordered implementation tasks
19. Acceptance criteria for every task
20. Verification requirements for every task
21. Phase exit criteria

### Task requirements

Each task must have:

- Task ID: `PN-XX`
- Objective
- Context
- Dependencies
- Scope
- Implementation requirements
- Acceptance criteria
- Verification requirements
- Expected files/components where appropriate
- Architecture impact
- Out of scope

Task ordering must be explicit.

### Constraints

- Do not implement any phase task.
- Do not modify application source code.
- Only create or update the planning documentation required for this task.

### After planning

1. Inspect the complete git diff.
2. Verify that the plan is consistent with the existing architecture.
3. Verify that no future-phase technology has leaked into the planned phase.
4. Verify that tasks are independently implementable.
5. Verify that every task has acceptance and verification criteria.

Then report:

- Phase objective
- Major architectural evolution
- Tasks created
- Task dependencies
- Technologies introduced
- Technologies explicitly deferred
- NFRs
- Risks
- ADRs required
- Phase exit criteria
- Files changed

Stop after creating the phase plan.

Do not implement the planned phase.
