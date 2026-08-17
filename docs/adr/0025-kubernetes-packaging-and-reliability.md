# ADR-0025: Kubernetes Packaging, Stateful Quorums, Replicated Ingress, and Multi-Node Reliability

- **Status:** Accepted
- **Date:** 2026-08-17
- **Deciders:** Architecture Review Board, Antigravity AI Engineering Harness
- **Phase:** Phase 16 — Local Kubernetes, Ingress, and Workload Orchestration
- **Consulted:** AGENTS.md, docs/constitution.md, Phase 15 Evidence Dossier, Phase 16 Plan

---

## 1. Context and Problem Statement

In Phase 14 and Phase 15, HyperScale Commerce established multi-replica stateless services (`app`, `order-query`), a 3-broker KRaft Kafka cluster (RF=3, $\text{min.isr}=2$), and a 3-node PostgreSQL 16 streaming replication cluster governed by Patroni with a 3-member `etcd` DCS cluster ($\text{RPO}=0$).

However, operating these components via Docker Compose introduces operational limits:
1. **No Declarative Self-Healing:** Failed processes or crashed nodes require manual script intervention or external watchdogs.
2. **No Logical Node Topology:** All containers share a flat, undifferentiated runtime placement domain without node anti-affinity or topology spread.
3. **Unsafe Voluntary Disruptions:** Rolling restarts or node drains can inadvertently breach quorum without PodDisruptionBudgets.
4. **Single Ingress Process:** HAProxy operated as a single container process.
5. **No Declarative Elasticity:** Autoscaling under load spikes requires manual container scaling.

We need an architecture to package and orchestrate the entire platform declaratively on Kubernetes while preserving all proven durability, consensus, and security invariants.

---

## 2. Alternatives Considered

| Architecture Option | Strengths | Weaknesses | Decision |
|---|---|---|---|
| **Option 1: Continue with Docker Compose + Custom Shell Daemons** | Zero learning curve; already functional in Phase 15. | Lacks scheduler, declarative convergence, rolling update policies, PDBs, HPA, and multi-node abstractions. | **REJECTED** |
| **Option 2: Immediate Cloud Kubernetes (EKS/GKE) + Managed PaaS (RDS/MSK)** | Fully managed infrastructure; production-scale networking. | Introduces heavy cloud costs, IAM complexity, vendor lock-in, and bypasses local reliability qualification; violates Phase 16 scope. | **REJECTED** |
| **Option 3: Local Multi-Node kind Cluster + Helm Packaging + Native Kubernetes Workloads** | Reproducible multi-node scheduler (3 control plane, 3 workers); standard Kubernetes APIs (StatefulSets, Deployments, PDBs, HPA, NetworkPolicies); preserves proven Kafka/Patroni consensus engines. | Logical multi-node environment bounded by single host physical failure domain. | **ACCEPTED** |

---

## 3. Decision Outcome

Adopt a **local multi-node `kind` Kubernetes cluster (3 control-plane nodes, 3 worker nodes)** packaged with **Helm 3.x**, structured into the following architectural tiers:

### 3.1 Cluster Topology & Node Architecture
- **Control Plane:** 3 control-plane nodes providing high availability for `kube-apiserver` and `etcd` control plane.
- **Worker Nodes:** 3 worker nodes (`worker`, `worker2`, `worker3`) providing distinct placement domains for anti-affinity and topology spread.

### 3.2 Stateless Application Tier
- **Deployments:** `app` and `order-query` packaged as Kubernetes Deployments with at least 3 initial replicas.
- **Rolling Updates:** Configured with `maxUnavailable: 0` and `maxSurge: 1` to ensure zero request loss during rolling updates.
- **Probes:**
  - `startupProbe`: `/actuator/health/liveness` with generous failure thresholds ($30\times 3\text{s}$) during Spring Boot initialization.
  - `readinessProbe`: `/actuator/health/readiness` ($3\text{s}$ interval) controlling endpoint participation in Services.
  - `livenessProbe`: `/actuator/health/liveness` ($5\text{s}$ interval) for pod restart on deadlocks.
- **Autoscaling (HPA):** HorizontalPodAutoscaler (HPA v2) scaling stateless pods between 3 and 8 replicas based on 70% CPU / memory utilization targets.

### 3.3 Stateful Quorum Tier (Preserved Consensus Invariants)
- **Kafka Cluster:** 3-pod `StatefulSet` with headless Service `kafka-headless` and individual `PersistentVolumeClaims` ($RF=3, \text{min.isr}=2$).
- **etcd DCS Cluster:** 3-pod `StatefulSet` with headless Service `etcd-headless` and individual PVCs.
- **Patroni / PostgreSQL 16 Cluster:** 3-pod `StatefulSet` with headless Service `postgres-ha-headless`, `synchronous_mode: true`, and `synchronous_standby_names = 'ANY 1 (...)'`. Patroni remains the authoritative primary election leader.
- **Disaster Recovery:** `pgBackRest` scheduled backup Jobs / CronJobs archiving to a dedicated `pgbackrest-repo` PVC.

### 3.4 Ingress & Rate-Limiting Tier
- **Replicated Ingress:** 2-pod HAProxy `StatefulSet` exposed via NodePort/LoadBalancer Service.
- **Peer State Synchronization:** Configured with HAProxy `peers` protocol synchronizing sliding-window stick-tables across ingress pods, guaranteeing rate limits cannot be bypassed or reset upon ingress failover.

### 3.5 Disruption & Resilience Controls
- **PodDisruptionBudgets (PDBs):**
  - Kafka: `minAvailable: 2` (preserves min.isr quorum).
  - etcd: `minAvailable: 2` (preserves DCS quorum).
  - PostgreSQL: `minAvailable: 2` (preserves synchronous replication).
  - Stateless Apps: `minAvailable: 2` (preserves capacity).
  - HAProxy Ingress: `minAvailable: 1` (preserves routing).

---

## 4. Consequences and Non-Claims

### Positive Consequences
- Declarative self-healing: crashed pods or partitioned workers are automatically rescheduled by Kubernetes.
- Zero-downtime rolling updates verified with active k6 traffic.
- Preserves $\text{RPO} = 0$ durability and split-brain immunity across all stateful tiers.

### Negative Consequences / Tradeoffs
- Higher local resource overhead: 6 `kind` nodes + Docker engine require at least 6-8GB RAM on the host.

### Explicit Non-Claims
- **Single Physical Host Limit:** All 6 `kind` nodes run on a single host Docker daemon. Multi-AZ, multi-rack, or cross-region physical failures remain out of scope for Phase 16.
- **No Cloud Managed PaaS:** Does not claim RDS/MSK managed service semantics.
