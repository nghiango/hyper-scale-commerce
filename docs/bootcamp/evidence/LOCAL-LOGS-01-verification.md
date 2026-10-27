# LOCAL-LOGS-01 verification

Date: 2026-10-27
Scope: isolated local centralized logs; owner requested Loki/Alloy/Grafana.
Architecture decision: ADR-0030 (Proposed; implementation owner-authorized).

## Static and native verification

- `bash local-ci/scripts/validate.sh`: PASS (Helm lint/render, logging persistence,
  namespace RBAC/no privileged host mounts, Grafana source, release/GitOps tests).
- Pinned Loki 3.7.9 ARM64 container `-verify-config=true`: PASS.
- Pinned Alloy v1.21.2 ARM64 container `validate`: PASS.
- Loki index digest 0db216b630ae1f348a1584c67f3cead8b4c1fc839169a39501db664744158e28;
  ARM64 manifest 1a779c5f714e795ac698b125fc265ae97292f792a91535fa29726073d03d5580.
- Alloy index digest 89a5a320168387336b9ac3c850609c3ba7757eadd4ae5f1d819deb35e9854b18;
  ARM64 manifest c760b7384b5f06f05cbd9a6e2cc3e5f60d5527d9437b03017dca0473df2d8c83.
- Existing Prometheus/Tempo/OTel/Kafka UI rendered Deployment documents unchanged.
- Grafana old database copied quiescent to 1Gi PVC; snapshot SHA-256 matched.
  No configured password reset. Temporary migration pod deleted.

## Live verification

Results below were captured after actual deployment verification.

### Verified live results

- GitOps commit 86dbfceb54c9997cb8d0df43a9200baf31a49f23; Argo platform
  Application Synced/Healthy. Remote application release 561acc0 preserved by
  rebasing after first push rejection. GitOps checkout clean after push.
- Grafana, Loki and Alloy each 1/1 ready. Existing monitoring deployments retained
  their old pod identities; only Grafana required the intentional data-source
  rollout. Loki was then deliberately restarted for persistence verification.
- Loki /ready returned `ready` from Grafana's pod. Queries for component app and
  order-query both returned success and five actual log lines (two app streams,
  one query stream in sampled results). Log content not copied into this report.
- Grafana provisioning logs confirmed `name=Loki uid=loki`. Network/query path
  from Grafana pod to Loki succeeded. Authenticated Grafana datasource-proxy/UI
  query was not exercised because the existing configured login differs from
  startup credentials; no password was reset. Existing database was preserved.
- RBAC impersonation checks: log-reader can get hyperscale pods/log; cannot get
  hyperscale secrets or default namespace pods/log. No host mount/ClusterRole.
- Loki replacement pod loki-dd65bdc58-cszg5 became Ready. Exact pre-restart app log
  timestamp remained queryable. Loki PVC UID unchanged:
  98df5a4f-fc2c-478b-8fdb-b15c06b7ebe6. All pre-existing namespace PVC UIDs retained;
  only new Loki/Grafana PVCs were added. No stateful application storage deleted.
- Grafana data PVC UID 7cb4a23c-b939-4b72-abe9-944389797b50; 1Gi Bound. Loki 10Gi Bound.
- Grafana localhost:3000 port-forward started for user access.

## Verification decision and limits

Local logging deployment PASS: real application log collection, query, persistence,
RBAC confinement and provisioning verified. Browser login remains a user check
using the existing password. Production durability, historical completeness,
retention expiry, saturation and log-to-trace linking were not qualified.
Grafana's optional plugin/update internet calls time out under existing network
isolation; built-in Loki provisioning and backend query path work without them.

Application-source modifications remain in the current java checkout, uncommitted;
merge/publish them into the Jenkins deployment branch before its next release so
chart copying does not remove logging. GitOps deployment itself is committed and
live. No application source branch was changed or force-pushed.
