# Current BootCamp Phase

Phase: 10

Name: Production Readiness, Operational Hardening & Final Certification

Status: APPROVED

Allowed technologies:

- Kotlin
- Spring Boot (Graceful Shutdown, Actuator, Security)
- Gradle (multi-module builds)
- PostgreSQL (HikariCP pool management)
- Flyway
- Docker (service images)
- Docker Compose
- Testcontainers
- ArchUnit (test-only)
- Kafka
- spring-kafka
- Testcontainers Kafka
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
- Autoscaling / dynamic pod replicas
- Cloud-managed PaaS infrastructure
- Elasticsearch / OpenSearch
- Event sourcing
- Microservice sprawl / unnecessary new deployables

Next milestone:

P10-01 — ADR-0016: Production Hardening, Security, and Lifecycle Strategy
