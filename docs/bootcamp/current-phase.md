# Current BootCamp Phase

Phase: 13

Name: Distributed Stream Operations, DLQ Replay & Out-of-Order Event Resilience

Status: COMPLETED

Allowed technologies:

- Kotlin
- Spring Boot (Web Filters, Cache Management, Admin Endpoints)
- Caffeine Cache (in-memory L1 caches and fixed-window rate limiting)
- Gradle (multi-module builds)
- PostgreSQL 16 (`SKIP LOCKED`, storage pruning, idempotency, and optimistic version columns)
- Flyway
- Docker (service images)
- Docker Compose
- Testcontainers
- ArchUnit (test-only)
- Kafka 3.7.0 (Dead Letter Queues, custom headers)
- spring-kafka
- Spring Data JDBC
- jOOQ
- Micrometer Tracing
- Brave
- SLF4J MDC
- k6 (test-only pinned container image)
- POSIX shell and Docker Engine CLI (test-only)
- Toxiproxy (test-only, digest-pinned)
- Prometheus Alerting Rule definitions (configuration-only)

Forbidden until later phases:

- Kubernetes (deferred to cloud deployment)
- Service mesh
- Heavy BPMN workflow engines (Temporal/Camunda)
- Distributed XA Two-Phase Commit transactions
- Autoscaling / dynamic pod replicas
- Cloud-managed PaaS infrastructure
- Elasticsearch / OpenSearch

Milestones:

- P13-01: Monotonic Aggregate Versioning & Out-of-Order Event Guard (COMPLETED)
- P13-02: Dead Letter Queue Inspection & Administrative Replay Engine (COMPLETED)
- P13-03: Per-Instance Client Rate Limiter Filter (COMPLETED)
- P13-04: Stream Operations & Out-of-Order Resilience Qualification (COMPLETED)
- P13-05: Phase 13 Review & Application-Level Platform Qualification (COMPLETED)

Next planned phase:

- Phase 14: Multi-Replica Runtime & Kafka High Availability
- Plan: `docs/bootcamp/phase-14-plan.md`
- Approval status: PROPOSED — NOT YET APPROVED FOR IMPLEMENTATION
- Until approval, Phase 13 remains the current completed phase and its
  technology constraints remain authoritative.
