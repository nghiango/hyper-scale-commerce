# Current BootCamp Phase

Phase: 18

Name: Kotlin/JVM Engineering Maturity & Concurrency Safety

Status: IN PROGRESS — REMEDIATION REQUIRED AFTER FAILED PHASE REVIEW

Allowed technologies:

- Kotlin 2.2.21
- Spring Boot 4.0.0 (Web Filters, Cache Management, Admin Endpoints, Configuration Properties)
- Caffeine Cache (in-memory L1 local caches)
- Redis 7.2 (Alpine, distributed L2 cache, Kubernetes StatefulSet, Lettuce client)
- Spring Data Redis / Spring Cache abstractions
- Spring Routing DataSource (`AbstractRoutingDataSource` for read/write splitting)
- Gradle (multi-module builds, shared convention build logic)
- PostgreSQL 16 (Patroni primary, synchronous standby, asynchronous read replicas, `SKIP LOCKED`)
- Patroni 3.x+ (consensus-based PostgreSQL HA and primary election daemon)
- etcd 3.5+ (3-node consensus distributed configuration store)
- pgBackRest (physical backup, continuous WAL archiving, point-in-time recovery)
- PostgreSQL JDBC Driver (multi-host URLs with `targetServerType=primary` and strict `secondary` targeting)
- HikariCP (dual connection pools for Primary and Read-Replicas)
- Flyway (primary-directed schema migrations)
- Docker & kind (Kubernetes in Docker, pinned multi-node clusters: 3 control-plane, 3 workers)
- Helm 3.x (packaging, templating, schema validation, values hierarchies)
- Kubernetes 1.29+ Workloads:
  - StatefulSets (Kafka KRaft, PostgreSQL/Patroni, etcd, Redis, HAProxy)
  - Deployments (Stateless `app`, `order-query`)
  - Headless Services, ClusterIP Services, NodePort Service abstractions
  - PersistentVolumeClaims & StorageClasses
  - HorizontalPodAutoscaler (HPA v2)
  - PodDisruptionBudgets (PDBs)
  - NetworkPolicies & RBAC
  - Pod Security Standards (Restricted, non-root containers)
- HAProxy 2.9 (ingress routing, peer-synchronized stick-table rate limiting)
- Kafka 3.7.0 (3-node KRaft quorum, RF=3, min ISR=2, cache invalidation broadcast topics)
- spring-kafka
- Spring Data JDBC
- jOOQ
- Micrometer Tracing & Brave
- SLF4J MDC
- k6 (pinned load testing container)
- POSIX shell, kubectl CLI, helm CLI
- Prometheus Alerting Rule definitions
- JDK 21 Diagnostic Tooling (Java Flight Recorder, `jcmd`, GC logs, thread dumps)
- Test-only `kotlinx-coroutines-core` and `kotlinx-coroutines-test` (pinned, structured concurrency experiments)
- Java 21 Virtual Threads (experimental comparison mode, configuration-gated)

Forbidden until later phases:

- Spring WebFlux / Reactor / R2DBC / reactive Redis / reactive Kafka
- Production coroutines without specific non-transactional use case & ADR approval
- Project Loom preview APIs
- GraalVM native image
- Cloud-managed ElastiCache / MemoryDB
- Cloud-managed Kubernetes (EKS, GKE, AKS)
- Cloud-managed PaaS infrastructure (RDS, Aurora, MSK)
- Service meshes (Istio, Linkerd)
- Dynamic cloud CSI provisioners (EBS, EFS, GCS)
- GitOps controllers (ArgoCD, Flux)
- Heavy BPMN workflow engines (Temporal, Camunda)
- Distributed XA Two-Phase Commit transactions
- Multi-region active-active clusters

Milestones:

- P18-01: Kotlin/JVM Baseline and ADR-0027 (REMEDIATION REQUIRED — PROFILING EVIDENCE INVALIDATED)
- P18-02: Shared Kotlin Compiler and Static-Analysis Policy (COMPLETED)
- P18-03: Type-Safe Configuration and Kotlin Boundary Hardening (COMPLETED)
- P18-04: Deterministic Concurrency and Context-Safety Verification (FUNCTIONAL GATES VERIFIED; DISTRIBUTED QUALIFICATION PENDING)
- P18-05: Structured-Concurrency and Execution-Model Qualification (FUNCTIONAL COMPARISON VERIFIED; PERFORMANCE/JFR GATES PENDING)
- P18-06: JVM Diagnostics, Profiling, and Operational Runbook (LOCAL FIXTURE VERIFIED; IN-POD/LOAD EVIDENCE PENDING)
- P18-07: Kotlin/JVM Load, Spike, Failure, and Soak Qualification (NOT QUALIFIED)
- P18-08: Phase 18 Evidence Dossier and Formal Phase Review (NOT COMPLETE)
