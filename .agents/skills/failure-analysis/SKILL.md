---
name: failure-analysis
description: Analyze and verify HyperScale Commerce behavior under dependency, infrastructure, network, data, and distributed-system failures. Use this skill to design failure experiments, execute safe verification, collect evidence, identify resilience gaps, and produce a failure-analysis report.
---

# Failure Analysis Skill

## Purpose

Use this skill to determine whether the current implementation behaves
correctly when components, dependencies, networks, or distributed-system
assumptions fail.

The goal is to verify that the system:

- fails predictably
- protects data integrity
- isolates failures
- avoids cascading failures
- handles retries safely
- handles duplicate operations safely
- recovers correctly
- provides sufficient observability
- maintains required availability and resilience targets

This skill is primarily for **analysis and verification**.

Do not modify application implementation unless the user explicitly asks for
remediation.

---

# 1. Required Context

Before starting:

1. Read `AGENTS.md`.
2. Read `docs/constitution.md`.
3. Read `docs/architecture.md`.
4. Read `docs/bootcamp/current-phase.md`.
5. Read the current phase requirements.
6. Read the relevant BootCamp task.
7. Read relevant ADRs in `docs/adr/`.
8. Inspect the implementation being analyzed.

Determine:

- current architectural phase
- relevant reliability requirements
- expected failure behavior
- consistency requirements
- recovery requirements
- available observability
- allowed failure-testing techniques

Do not invent expected behavior.

If expected failure behavior is not documented, report it as a
**requirement gap**.

---

# 2. Scope

Before testing, identify:

```text
Task:
Phase:
Component(s):
Failure scenario:
Environment:
Expected behavior:
Recovery requirement:
Relevant architecture:
Relevant ADRs: