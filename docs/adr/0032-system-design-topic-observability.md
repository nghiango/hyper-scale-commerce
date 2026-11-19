# ADR-0032: Observe system-design practise topic namespaces

Status: Proposed
Date: 2026-11-19
Task: LOCAL-TOPICS-OBS-01
Amends: ADR-0030 (namespace-scoped log collection)

## Context and authorization

The separate `system-design-practise` repository deploys one practise topic at a
time into a `topic-<slug>` namespace on this cluster, reusing the shared Kafka,
Patroni and Redis. Its metrics and logs must reach the existing Prometheus, Loki
and Grafana. Prometheus and Alloy can only discover pods in namespaces where they
hold read RBAC, and their namespace selectors accept exact names or all
namespaces, not a `topic-*` pattern. Owner approved option A on 2026-11-19. This
is a local observability exception; no business architecture or phase change.

## Alternatives

Per-topic Prometheus/Alloy: about two extra pods per topic and no shared
dashboards. Namespace Roles created by each topic chart plus a static list of six
topic namespaces in the platform config: keeps a no-ClusterRole rule, but five
listed namespaces are always absent (only one topic is online), producing
continuous forbidden/not-found discovery errors in Loki, and every new topic
needs a platform change. Two read-only ClusterRoles with namespace filtering:
chosen.

## Decision

Add ClusterRole/ClusterRoleBinding `local-monitoring-topics` (pods, services,
endpoints: get/list/watch) bound to `local-monitoring`, and
`local-log-reader-topics` (pods: get/list/watch; pods/log: get) bound to
`local-log-reader`. A new Prometheus `topics` job and Alloy `topic_pods`
pipeline discover cluster-wide but keep only namespaces matching `topic-.+`, and
add a `topic` label from `system-design/topic`. Existing `hyperscale` jobs,
Roles and pipelines are unchanged. A "System Design Topics" Grafana dashboard
is provisioned. Network access into topic namespaces is owned by the topic chart.

`verify-release.py` replaces "no ClusterRole" with an exact allow-list: only
these two ClusterRoles/Bindings, verbs limited to get/list/watch, and no
secrets, configmaps or wildcards.

## Consequences and failures

Both service accounts can read pod metadata and pod logs in every namespace,
including `kube-system` and `argocd`; filtering limits collection, not access.
They cannot read Secrets or modify anything. Cluster-wide watches add modest API
load on a six-node kind cluster. Offline topics produce no targets and no errors.

## Verification and rollback

`local-ci/scripts/test-platform-topics.py` (run by `validate.sh`) checks the
jobs, filters, labels, bindings and read-only verbs; `verify-release.py` rejects
any other or broader ClusterRole. Rollback: revert this platform change and
release with `RELEASE_SCOPE=platform`; topic namespaces then stop being
observed, with no effect on `hyperscale` collection.
