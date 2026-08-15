---
name: production-readiness
description: Evaluate whether the HyperScale Commerce system is safe and operationally ready for production. Use this skill to review reliability, security, observability, performance, resilience, deployment, recovery, data integrity, configuration, documentation, and operational readiness before production release.
---

# Production Readiness Skill

## Purpose

Use this skill to determine whether HyperScale Commerce is ready to operate in
a production environment.

The review must evaluate the system as an operational platform, not merely as
an application that builds and passes tests.

Production readiness requires evidence that the system can:

- operate reliably
- handle expected production traffic
- recover from failures
- protect data integrity
- scale appropriately
- be monitored and operated
- be deployed safely
- be rolled back safely
- handle dependency failures
- protect secrets and sensitive configuration
- provide sufficient operational documentation

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
5. Read the relevant phase requirements.
6. Read relevant ADRs in `docs/adr/`.
7. Inspect the application implementation.
8. Inspect deployment and infrastructure configuration.
9. Inspect CI/CD configuration.
10. Inspect tests.
11. Inspect performance evidence.
12. Inspect failure-analysis evidence.
13. Inspect operational documentation.

Determine:

- production architecture
- deployment model
- runtime environment
- availability requirements
- performance requirements
- recovery requirements
- data integrity requirements
- security requirements
- observability requirements
- operational ownership
- backup and recovery strategy
- rollback strategy
- scaling strategy

Do not invent production requirements.

If a critical requirement is not documented, report it as a
**production-readiness gap**.

---

# 2. Scope

Define the production-readiness scope:

```text
System:
Version / Commit:
Environment:
Deployment Model:
Production Architecture:
Expected Traffic:
Expected Peak Traffic:
Availability Target:
Latency Target:
Recovery Target:
Data Criticality:
External Dependencies:
Operational Owner: