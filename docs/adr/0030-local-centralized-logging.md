# ADR-0030: Local centralized logging with Loki and Alloy

Status: Proposed
Date: 2026-10-27
Task: LOCAL-LOGS-01

## Context and authorization

The owner requested Loki after discussing Alloy log collection and Grafana
search. This is an approved local observability extension to ADR-0028, not a
phase advancement. kubectl logs alone cannot search across removed pods.
Implementation is owner-authorized; formal architecture review remains pending.

## Alternatives

Keep kubectl/k9s only: no new services, but no centralized history. Elasticsearch
adds another datastore and greater local resource cost. Node-file collection
avoids Kubernetes API streaming load but requires host access and a DaemonSet.
Namespace-scoped Alloy API collection fits this small local cluster without
privileged containers or host filesystem mounts.

## Decision

Deploy single-replica pinned ARM64 Loki and Alloy via the existing platform
GitOps chart. Loki uses TSDB schema v13 and filesystem storage on a 10Gi PVC;
compactor retention is 24 hours. Alloy reads only hyperscale pods/log through a
dedicated namespaced Role and forwards to Loki. Labels are cluster, namespace,
pod, container and component; log contents/trace IDs are not index labels.
Exclude Loki/Alloy themselves to prevent log feedback. No log parsing or business
logging changes. Grafana provisions a Loki source. Loki is ClusterIP-only without
authentication in this isolated local environment, with ingress limited to
Grafana/Alloy. Do not expose it publicly. Resource limits bound CPU and memory.

## Consequences and failures

API collection adds kubelet/API traffic. Logs can be duplicated or missed during
collector outages, restarts or pod log rotation; Alloy local state is disposable.
Collection covers current namespace pod logs, not host logs or external Jenkins.
Loki persists across pod restart, but kind-local storage is not replicated or
backed up and does not survive arbitrary node/cluster deletion. One replica means
query/ingestion downtime on restart. Retention is asynchronous and does not cap
disk usage; 10Gi can fill sooner under heavy logging. No zero-loss guarantee.
Grafana needs a restart for the new provisioned source; its existing local data
remains subject to the disposable monitoring storage policy in ADR-0028.

## Operation, rollback and verification

Use Grafana Explore → Loki and namespace/component selectors. Inspect Alloy logs
for collection/write errors and Loki /ready for readiness. Check PVC disk usage.
Revert the platform GitOps change to stop collection; pruning is disabled, so
explicit resource cleanup is separate. Preserve loki-data unless its deletion is
explicitly authorized. Validate configs with the pinned image binaries, verify
RBAC confinement and app/query log retrieval, and restart Loki to confirm retained
query data. Evidence: docs/bootcamp/evidence/LOCAL-LOGS-01-verification.md.

## References

- https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.kubernetes/
- https://grafana.com/docs/loki/latest/operations/storage/filesystem/
- https://grafana.com/docs/loki/latest/operations/storage/retention/

### Existing Grafana state preservation

Live Grafana rejected startup credentials, and its database was on emptyDir.
To avoid resetting its configured account or discarding dashboards during the
required restart, migrate a quiesced snapshot to a dedicated 1Gi Grafana PVC.
Use Recreate for the single SQLite writer. This is local state preservation for
the logging integration, not replicated storage or a general backup guarantee.
