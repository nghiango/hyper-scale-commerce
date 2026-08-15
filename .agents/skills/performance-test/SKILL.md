---
name: performance-test
description: Design, execute, analyze, and document performance tests for the HyperScale Commerce platform. Use this skill to verify latency, throughput, concurrency, scalability, resource utilization, traffic-spike behavior, and performance targets with reproducible evidence.
---

# Performance Test Skill

## Purpose

Use this skill to determine whether HyperScale Commerce meets the performance
requirements defined for the current BootCamp phase.

The long-term platform targets are:

- 10,000+ concurrent users
- sub-200ms latency
- 5x traffic spikes
- 99.9% availability

These are evolutionary targets.

Do NOT require the final targets in an earlier BootCamp phase unless the
current phase explicitly requires them.

This skill is responsible for:

- performance-test design
- workload modeling
- baseline measurement
- load testing
- stress testing
- spike testing
- scalability testing
- bottleneck analysis
- resource analysis
- performance evidence

This skill MUST NOT optimize or modify implementation unless the user
explicitly requests remediation.

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
8. Inspect the implementation being tested.

Determine:

- current architectural phase
- performance requirements
- latency targets
- throughput targets
- concurrency targets
- availability targets
- expected workload
- relevant bottlenecks
- infrastructure constraints
- available observability

Do not invent performance requirements.

If a required performance target is not documented, report it as a
**requirement gap**.

---

# 2. Scope

Before testing, define:

```text
Task:
Phase:
Environment:
System Under Test:
Endpoints / Workflows:
User Profile:
Target Concurrency:
Target Throughput:
Latency Target:
Duration:
Traffic Pattern:
Success Criteria: