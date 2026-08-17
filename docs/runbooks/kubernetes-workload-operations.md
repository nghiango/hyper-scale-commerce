# Runbook: Kubernetes Workload, Node & Resilience Operations

## Overview

In Phase 16 (ADR-0025), HyperScale Commerce is orchestrated on a multi-node Kubernetes cluster (3 control-plane nodes, 3 worker nodes) with Helm packaging, StatefulSets for quorums, Deployments for stateless services, PodDisruptionBudgets, and HPA.

This runbook guides operators through cluster diagnostics, incident response, voluntary node drains, rollout rollbacks, and capacity triage.

---

## 1. Quick Diagnostics

```bash
# Inspect all cluster nodes
kubectl get nodes -o wide

# Check all pods, status, and restarts in hyperscale namespace
kubectl get pods -n hyperscale -o wide

# Check PodDisruptionBudget allowable disruptions
kubectl get pdb -n hyperscale

# Check HorizontalPodAutoscaler utilization and replica counts
kubectl get hpa -n hyperscale

# Inspect HAProxy ingress peer connection state
curl -s http://localhost:8404/stats
```

---

## 2. Incident Response Procedures

### Alert: `K8sPodCrashLooping`
- **Impact:** Service capacity degraded or startup failure.
- **Triage:**
  1. Inspect pod events: `kubectl describe pod <pod-name> -n hyperscale`
  2. Inspect container logs: `kubectl logs <pod-name> -n hyperscale --previous`
  3. Verify database connectivity (`SPRING_DATASOURCE_URL`) and Kafka reachability.

---

### Alert: `K8sContainerOOMKilled`
- **Impact:** Container abruptly killed by the Linux kernel OOM killer due to memory limit breach.
- **Triage:**
  1. Identify memory usage: `kubectl top pod <pod-name> -n hyperscale`
  2. If JVM heap or in-memory Caffeine cache size expanded beyond memory limits, tune JVM max RAM percentage (`-XX:MaxRAMPercentage=75.0`) or increase Helm `resources.limits.memory` in `values.yaml`.

---

### Alert: `K8sNodeNotReady`
- **Impact:** Worker node lost. Kubernetes automatically evicts stateless pods and reschedules them to surviving worker nodes according to topology spread constraints.
- **Triage:**
  1. Check node conditions: `kubectl describe node <node-name>`
  2. If running on local kind, inspect docker container status: `docker inspect <node-container-name>`.
  3. If node is permanently unrecoverable, PDBs will protect stateful quorums while stateful pods are reassigned.

---

## 3. Maintenance Procedures

### Performing a Safe Voluntary Node Drain
Before node maintenance:
```bash
kubectl drain <worker-node-name> --ignore-daemonsets --delete-emptydir-data
```
*Kubernetes enforces PodDisruptionBudgets (`minAvailable: 2`), ensuring drains pause if a quorum would be endangered.*

### Rollout Rollback Procedure
If a bad deployment is detected under traffic:
```bash
# Check rollout history
kubectl rollout history deployment/app -n hyperscale

# Instantly rollback to previous stable revision
kubectl rollout undo deployment/app -n hyperscale
```
