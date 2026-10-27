# ADR-0029: Local Kafka UI and k9s

Status: Proposed
Date: 2026-10-27
Task: LOCAL-OPS-01

## Context and authorization

The owner explicitly requested Kafka UI and k9s for the local environment.
Kafka resources already exist in the approved commerce chart but have not been
started because the application release remains unqualified. The dashboard
needs live brokers without promoting pending-ci application images.

## Alternatives

Kafka CLI alone avoids another workload but does not provide the requested
browser dashboard. A laptop-hosted dashboard requires exposing Kafka listeners
and translating advertised addresses. A Kubernetes-local Kafbat UI can use the
existing headless service and is accessible through a loopback port-forward.
k9s already exists locally, so reinstalling/upgrading it adds no task value.

## Decision

Use maintained Kafbat Kafka UI, pinned to v1.5.0 and verified for ARM64, in the
platform Helm chart. Configure the hyperscale cluster read-only, disable dynamic
configuration, apply resource limits and retain restricted non-root container
settings. Expose only a ClusterIP Service plus localhost:8082 port-forward.
No public ingress or credentials are introduced for this loopback dashboard.
Kafka UI read-only mode is a UI/API safeguard, not a substitute for broker ACLs;
the existing local broker configuration uses plaintext internal listeners.

Start only the existing Kafka StatefulSet, Services, PDB and service account
using an Argo resource-selective sync. Kafka storage remains three local 5 Gi
PVCs, with the existing RF=3/min ISR=2 configuration. The inherited 7.6.0
image was found to provide Kafka 3.6; correct it to Confluent 7.7.0, the phase-
approved Kafka 3.7 line, with a PVC-preserving selective broker rollout. Keep commerce auto-sync
absent and do not sync app/query/PostgreSQL/Redis resources. This adds a running
local dependency for inspection, not a qualified application release or HA proof.
The UI remains owned by the existing platform Argo Application.

Provide a k9s launcher with an explicit kind context and namespace and read-only
mode. Do not replace the user's global kubeconfig or existing k9s customizations.

## Operations and failure modes

Run the dashboard forward to access it; restarting a forward is sufficient when
it exits. The UI can report offline brokers during startup or broker faults.
Do not lower broker quorum/replication to make the dashboard appear healthy.
Kubernetes and k9s remain on one Docker host. UI memory/CPU and Kafka PVC costs
are local and do not establish production durability or availability.

Stop the port-forward to remove browser access. Preserve Kafka PVCs if stopping
brokers. Remove UI through a reviewed platform GitOps change if no longer needed.
Do not use the dashboard setup to enable full commerce deployment.

## Verification

See docs/operations/local-cicd.md and the LOCAL-OPS-01 verification report for
actual image, broker, UI and k9s results. This ADR records implementation
permission from the owner; formal architectural review status remains Proposed.
