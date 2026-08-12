---
name: security-review
description: Review the HyperScale Commerce platform for security vulnerabilities, insecure architecture, excessive privileges, secret exposure, unsafe configuration, dependency risks, API weaknesses, data protection issues, and production security gaps. Use this skill for security-focused reviews without modifying implementation unless explicitly requested.
---

# Security Review Skill

## Purpose

Use this skill to determine whether the HyperScale Commerce system satisfies
its security requirements for the current BootCamp phase or production target.

This skill focuses on:

- application security
- API security
- authentication and authorization
- secrets management
- data protection
- dependency security
- cloud/IAM security
- infrastructure security
- event-driven security
- configuration security
- logging and auditability
- security boundaries
- common vulnerability classes
- production security readiness

This skill is a **security review and verification procedure**.

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
8. Inspect infrastructure configuration.
9. Inspect CI/CD configuration.
10. Inspect dependency configuration.
11. Inspect authentication and authorization implementation.
12. Inspect secrets and configuration handling.
13. Inspect relevant security evidence and previous findings.

Determine:

- security requirements
- trust boundaries
- protected resources
- actors
- authentication model
- authorization model
- sensitive data
- external dependencies
- cloud permissions
- security controls
- applicable compliance requirements
- production security requirements

Do not invent security requirements.

If a critical security requirement is not documented, report it as a
**security requirement gap**.

---

# 2. Scope

Define the review scope:

```text
System:
Version / Commit:
Environment:
Phase:
Architecture:
APIs:
Authentication:
Authorization:
Data Stores:
External Dependencies:
Cloud Resources:
Messaging:
CI/CD:
Secrets Management:
Security Requirements: