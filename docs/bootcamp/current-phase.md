# Current BootCamp Phase

Phase: 08

Name: Load Engineering

Status: APPROVED

Allowed technologies:

- Kotlin
- Spring Boot
- Gradle (multi-module builds)
- PostgreSQL
- Flyway
- Docker (service images)
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
- POSIX shell and existing HTTP/Docker interfaces (test-only orchestration)

Forbidden until later phases:

- Redis
- Kubernetes
- API gateway
- Service discovery
- Service mesh
- Elasticsearch
- Event sourcing
- Separate physical databases per service
- Synchronous inter-service calls (REST/gRPC) across deployables
- Prometheus/Grafana servers, central APM, and log aggregation
- Distributed load-generator infrastructure
- Network-partition and randomized fault injection

Next milestone:

P8-01 — ADR: load-test strategy and qualification model
