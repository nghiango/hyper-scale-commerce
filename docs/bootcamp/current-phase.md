# Current BootCamp Phase

Phase: 11

Name: Advanced Distributed Workflows, Saga Compensations & Overload Protection

Status: COMPLETED

Allowed technologies:

- Kotlin
- Spring Boot (Web Filters, Transaction Management)
- Gradle (multi-module builds)
- PostgreSQL 16 (Idempotency table, row-level locks)
- Flyway
- Docker (service images)
- Docker Compose
- Testcontainers
- ArchUnit (test-only)
- Kafka 3.7.0
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

- P11-01: API Idempotency Key Engine (COMPLETED)
- P11-02: Choreographed Saga Compensations (COMPLETED)
- P11-03: Priority-Tiered Adaptive Load Shedder (COMPLETED)
- P11-04: Event Schema Evolution & Compatibility Suite (COMPLETED)
- P11-05: Distributed Workflows Chaos & Verification (COMPLETED)
- P11-06: Phase 11 Consolidation & Review (COMPLETED)
