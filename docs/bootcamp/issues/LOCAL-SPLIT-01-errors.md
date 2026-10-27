# LOCAL-SPLIT-01 issue history

## 2026-10-27 — Rendered ownership assertion

Initial verification counted `kind: Deployment` substrings and included HPA
scaleTargetRef entries, failing its expected resource count. Replaced with an
anchored top-level kind match. This was a test assertion error, not duplicate
Deployment resources. Rerun complete local validation before publication.

Resolution: complete Helm/release/GitOps and scoped publisher validation passed.
Initial resource-owner inspection looked for Argo's usual tracking key and found
none; read argocd-cm, which explicitly configures argocd.argoproj.io/instance.
Correct key confirms infrastructure ownership. Argo Applications all Synced/Healthy;
existing workload/PVC UIDs and pod templates unchanged. No storage deletion.
