---
name: implement-bootcamp-task
description: Implement exactly one approved HyperScale Commerce BootCamp task with strict scope control, verification, architecture compliance, and evidence reporting.
---

# Implement BootCamp Task

Use this workflow when implementing an approved task from the current
HyperScale Commerce BootCamp phase.

## Preconditions

The task must already be approved.

The task must exist in:

docs/bootcamp/phase-XX-plan.md

Do not implement an unapproved task.

---

## Step 1 — Load Context

Read:

1. AGENTS.md
2. docs/constitution.md
3. docs/requirements.md
4. docs/architecture.md
5. docs/bootcamp/current-phase.md
6. the relevant phase specification
7. the approved task definition

Do not rely on memory from previous tasks.

---

## Step 2 — Confirm Scope

Before modifying code, determine:

- exact task ID
- objective
- acceptance criteria
- files/components expected to change
- dependencies
- verification requirements
- architectural impact

Only implement the requested task.

Do not implement future tasks.

Do not perform unrelated refactoring.

---

## Step 3 — Inspect Before Editing

Inspect the existing implementation.

Understand:

- project structure
- existing patterns
- configuration
- dependencies
- tests
- architecture boundaries

Prefer existing project conventions over introducing new patterns.

---

## Step 4 — Implement the Smallest Solution

Implement only what is required to satisfy the task.

Rules:

- avoid speculative abstractions
- avoid unnecessary dependencies
- avoid unrelated refactoring
- do not introduce technologies from future phases
- preserve existing architecture
- follow AGENTS.md

If implementation requires an architectural change that was not part of the
approved task, stop and report it instead of silently expanding scope.

---

## Step 5 — Verify

Run the narrowest relevant verification first.

Then run the broader verification required by the repository.

Examples:

- compilation
- unit tests
- integration tests
- architecture tests
- static analysis
- formatting
- application startup

Do not claim success without executing verification.

---

## Step 6 — Failure Recording
If implementation or verification encounters a meaningful failure:

- Stop the current operation.
- Capture the relevant error.
- Invoke the record-task-issue skill for the current task.
- Investigate the failure.
- Determine the root cause if possible.
- Implement the smallest appropriate fix.
- Run verification again.
- Record the result.
- Continue only if verification succeeds.

Error history MUST be maintained at:

docs/bootcamp/issues/<TASK-ID>-errors.md

Do not silently retry.

Do not discard failed attempts.

Do not overwrite previous error history.

---

## Step 7 - Bounded Retry

Retries are allowed only when there is a reasonable basis for believing that
the failure can be resolved.

Do not repeatedly make arbitrary changes.

For the same root cause, track attempts:

```
Attempt 1 → FAILED
Attempt 2 → FAILED
Attempt 3 → FAILED
```

After three unsuccessful attempts for the same root cause:

- Invoke record-task-issue.
- Stop implementation.
- Do not continue modifying code.
- Report the repeated failure.
- State that human or architectural review is required.

Never enter an infinite:

fix → test → fail → fix → test → fail

loop.

---

## Step 8 — Inspect the Diff

Review:

```bash
git status
git diff
```

## Step 9 — Verify the Task

After implementation:

1. Complete the implementation verification described above.
2. Invoke the `verify-task` skill for the current task.
3. Do not modify the implementation as part of verification.

If verification fails:

- stop
- report the failed acceptance criteria
- report the findings
- do not start another task

If verification succeeds:

- identify the next approved task in the current phase plan
- confirm that the next task is not already completed
- confirm that its dependencies are satisfied
- start the next task using this same workflow

Do not skip tasks.

Do not select tasks from a future phase.

Do not implement multiple tasks in parallel.

---

## Step 10 — Select the Next Task

After a successful verification:

1. Read the current phase plan.
2. Find the next incomplete approved task.
3. Check its dependencies.
4. Confirm that all required previous tasks are verified.
5. Select that task.
6. Continue using `implement-bootcamp-task`.

If there is no remaining task in the current phase:

- stop
- report that the phase implementation tasks are complete
- do not automatically start the next phase
- recommend running `phase-review`

---

## Step 11 — Stop Conditions

Stop immediately when:

- the current task fails verification
- an acceptance criterion cannot be satisfied
- an architectural decision requires approval
- an unexpected dependency is discovered
- a required task dependency is incomplete
- the next task is not approved
- the next task is ambiguous
- a future-phase technology would be required
- the current phase has no remaining tasks

Do not guess.

---

## Step 12 — Phase Boundary

When all tasks in the current phase have been successfully implemented and
verified:

```text
Task N
  ↓
verify-task PASS
  ↓
No remaining tasks
  ↓
STOP