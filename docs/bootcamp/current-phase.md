# Current BootCamp Phase

Phase: 17

Name: Distributed Multi-Level Caching & Read-Replica Scaling

Status: COMPLETED — PHASE REVIEW PASSED

Review record: [Phase 17 Review — Passed](evidence/p17-phase-review.md)

Allowed technologies:

- Kotlin
- Spring Boot (Web Filters, Cache Management, Admin Endpoints)
- Caffeine Cache (in-memory L1 local caches)
- Redis 7.2 (Alpine, distributed L2 cache, Kubernetes StatefulSet, Lettuce client)
- Spring Data Redis / Spring Cache abstractions
- Spring Routing DataSource (`AbstractRoutingDataSource` for read/write splitting)
- Gradle (multi-module builds)
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

Forbidden until later phases:

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

- P17-01: Architecture Decision Record ADR-0026 & Plan Approval (COMPLETED)
- P17-02: Redis Distributed L2 Cache Packaging on Kubernetes (COMPLETED)
- P17-03: Multi-Level Near-Cache Implementation (L1 Caffeine + L2 Redis) (COMPLETED)
- P17-04: Event-Driven Cache Invalidation Bus (COMPLETED)
- P17-05: PostgreSQL Read/Write Splitting & Dynamic DataSource Routing (COMPLETED)
- P17-06: Cache & Replica Observability, Alerts & Runbooks (COMPLETED)
- P17-07: High-Concurrency Performance, Fault Injection & Scaling Qualification (COMPLETED)
- P17-08: Phase 17 Evidence Dossier and Phase Review (COMPLETED)
