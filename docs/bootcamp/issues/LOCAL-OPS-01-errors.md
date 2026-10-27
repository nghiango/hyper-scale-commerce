# LOCAL-OPS-01 error history

## 2026-10-27 — k9s first-run filesystem initialization

The sandboxed version/help check could not create the standard k9s log directory
under ~/Library/Application Support/k9s. Re-ran with approved local filesystem
access, confirmed existing ARM64 k9s 0.51.0, and opened the project-scoped TUI in
read-only mode. It displayed the correct kind context and namespace and exited
cleanly. No package upgrade or kubeconfig replacement was required.

## 2026-10-27 — Existing broker image/phase version mismatch

UI and quorum checks succeeded with the existing chart's cp-kafka:7.6.0, but
live metadata and Confluent release notes identify that as Kafka 3.6. The
higher-priority current-phase documentation specifies Kafka 3.7. Correct the
chart's broker image to the 7.7.0 / Kafka 3.7 line, verify ARM64 support, and
selectively roll Kafka only while retaining existing PVCs. This aligns code
with the already-approved phase rather than introducing a new technology or
relaxing the application promotion gate. No topic or volume deletion is used.

## 2026-10-27 — Selective rollout readiness race

For an already-existing StatefulSet, kubectl rollout status can observe the old
healthy generation before Argo applies an asynchronous operation. The first
version-alignment request returned before the new manifest was applied. Add a
bounded wait for Argo's operation and syncResult to report the exact requested
revision as Succeeded before checking Kubernetes rollout. Verify actual pod
image tags and unchanged PVCs rather than trusting the early rollout message.

## 2026-10-27 — Resolution and completion verification

Kafka-only rollout completed on all three cp-kafka:7.7.0 brokers. All original
PVCs remain Bound with unchanged volume IDs. KRaft voters are [1,2,3] with zero
follower lag at the final snapshot. The corrected selective-sync helper also
passed an idempotent same-revision check, using a unique request identifier to
avoid accepting an old succeeded operation. It rejects active/queued operations.
The metadata feature level remains 3.6-IV2 for compatibility, independently of
the now-correct broker binary/image line.

Kafka UI page and cluster/broker APIs returned HTTP 200; cluster ONLINE,
brokerCount=3, readOnly=true. The browser showed the dashboard and three brokers.
k9s read-only TUI showed the correct kind context and hyperscale pods and exited
cleanly. Platform is Synced/Healthy; commerce auto-sync remains absent and no
app/query/PostgreSQL/Redis workload was started. No unresolved task failure.
