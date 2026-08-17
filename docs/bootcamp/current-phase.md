# Current BootCamp Phase

Phase: 12

Name: High-Throughput Caching, Multi-Replica Scheduling & Storage Lifecycle Management

Status: COMPLETED

Allowed technologies:

- Kotlin
- Spring Boot (Web Filters, Cache Management)
- Caffeine Cache (in-memory L1 cache)
- Gradle (multi-module builds)
- PostgreSQL 16 (FOR UPDATE SKIP LOCKED, batch pruning)
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

- P12-01: Lock-Free Multi-Replica Outbox Polling (SKIP LOCKED) (COMPLETED)
- P12-02: Multi-Tier Caching & Cache Stampede Protection (COMPLETED)
- P12-03: Event-Driven Cache Invalidation (COMPLETED)
- P12-04: Automated Storage Lifecycle & Data Pruning Engine (COMPLETED)
- P12-05: High-Throughput Caching & Pruning Qualification (COMPLETED)
- P12-06: Phase 12 Review & Final Platform Dossier (COMPLETED)
