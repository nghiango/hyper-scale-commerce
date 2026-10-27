# LOCAL-SPLIT-01 verification

Date: 2026-10-27
Scope: owner-requested separation of application and infrastructure delivery.

Static validation: `bash local-ci/scripts/validate.sh` passed Helm lint/render,
existing release/monitoring/logging invariants, disjoint ownership checks and local
bare-Git publication tests. The test bootstraps all scopes, publishes a second
application revision, and verifies infrastructure chart tree, infrastructure
values and platform chart tree remain byte-identical. No application code changed.

Live migration preparation: generated disjoint charts from the deployed commerce
snapshot, retaining current images/values. Parsed Helm manifests into semantic
objects: 8 application + 36 infrastructure resources exactly equal the prior 44
resources, with no identity overlap. No pod spec changes introduced by migration.
Live results appended after ownership reconciliation.

## Live results

GitOps commit 2b83a8a was pushed without forcing. Existing Application was paused,
its source changed to charts/application, then the independent infrastructure
Application was registered and auto-sync resumed. Pruning stayed disabled.

All three Applications report Synced/Healthy:

- hyperscale-local → charts/application
- hyperscale-local-infrastructure → charts/infrastructure
- hyperscale-local-platform → charts/platform

The cluster uses tracking label `argocd.argoproj.io/instance`, configured in
argocd-cm. Kafka now carries hyperscale-local-infrastructure ownership. Other
resource ownership checked against each Application's desired partition. No
resource identity overlaps in rendered manifests. Every pre-existing Deployment,
StatefulSet and PVC retained its UID; every workload pod template is identical
to its pre-migration snapshot. No new image tags or rollout changes introduced.

Scoped image publication test uses a fake Docker binary and confirms exact image
selection: application={app,order-query}, infrastructure={patroni}, platform={},
bootstrap={app,order-query,patroni}; all actual build commands target ARM64/push.
Complete local validation passed after the last implementation change. Diff
whitespace checks passed. Legacy commerce files retained as an unwatched snapshot.

## Remaining integration prerequisite

The source checkout is the owner's java branch. Updated Jenkinsfile/generator/
publisher and documentation are saved locally, uncommitted. They must reach the
configured Jenkins deployment branch before normal automated application delivery
uses the scoped paths. No source main force-push, branch switch, application
build or new image publication was performed. Live GitOps ownership separation is
verified; deployment of the new Jenkins definition is not claimed.
