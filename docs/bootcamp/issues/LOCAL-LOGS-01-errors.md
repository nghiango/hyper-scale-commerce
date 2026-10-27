# LOCAL-LOGS-01 issue history

## 2026-10-27 — Grafana startup credential differs from live login

Two read-only API checks using startup environment credentials (including newline
normalization) returned HTTP 401. No password reset or further login retries used.
To preserve the configured login and dashboards across the required datasource
restart, migrated Grafana state from emptyDir to a dedicated 1Gi PVC. Briefly
paused PID 1 during tar streaming and checksum comparison; finally resumed it.
Source/destination grafana.db SHA-256 matched. Migration helper pod removed.

## 2026-10-27 — Chart copy path correction

Initial copy calculation selected a directory one level above the intended GitOps
checkout. No Git operation or cluster application used that directory. Corrected
the target to the existing sibling checkout, removed only matching files created
by that command and empty directories, and reran Helm lint and diff checks.

## 2026-10-27 — Verification tooling

Host Python does not include PyYAML. Used Helm lint/config validation and
rendered-document comparison without adding a dependency. Pinned native ARM64
Loki and Alloy binaries accepted the supplied configurations.

## 2026-10-27 — Concurrent GitOps update

First logging push was rejected as non-fast-forward: remote main advanced during
implementation. No force push used. Fetch and inspect remote commits, then rebase
the isolated logging change on the new main and validate before retrying.

Resolution: rebased logging onto 561acc0, validated and pushed 86dbfce. Argo reports
Synced/Healthy; app/query log queries and exact pre-restart timestamp persistence
passed. All pre-existing PVC identities unchanged. Grafana source provisioned;
authenticated UI/proxy check remains unexecuted with the user's configured login.
Optional Grafana plugin/update internet calls timed out under existing network
isolation, without preventing built-in Loki provisioning/query access.
