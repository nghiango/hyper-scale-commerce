---
name: record-task-issue
description: Record and preserve implementation and verification failures encountered while executing a HyperScale Commerce BootCamp task. Maintains an append-only error history and creates a permanent task completion record after successful implementation and verification. Documentation-only; never modifies application code.
---

# Record BootCamp Task Issue

## Purpose

Use this skill when an issue, failure, unexpected behavior, failed verification,
or implementation problem is encountered while executing a HyperScale Commerce
BootCamp task.

This skill maintains two types of records:

1. **Error history** — records problems and failed attempts.
2. **Task record** — records the final implementation, verification, problems,
   resolutions, and lessons learned after the task succeeds.

The purpose is to preserve an auditable engineering history.

The records must allow a future engineer or AI agent to understand:

- what went wrong
- when it went wrong
- what was attempted
- why the attempt failed
- what the root cause was
- how the problem was resolved
- how the resolution was verified
- what was learned

This skill is **documentation-only**.

It MUST NOT fix implementation problems.

It MUST NOT modify application code.

---

# Input

The task identifier must be provided.

Example:

```text
@skills record-task-issue P1-03