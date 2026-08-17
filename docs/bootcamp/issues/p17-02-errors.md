# P17-02 Error History

## 2026-08-17 — Helm verification unavailable

- **Operation:** `make k8s-redis-verify`
- **Result:** FAILED before manifest rendering.
- **Error:** `helm: command not found`
- **Root cause:** The verification script required a host-installed Helm binary,
  but Helm is not installed in the execution environment.
- **Impact:** Redis templates, PDB coverage, and rendered Kubernetes invariants
  were not verified. P17-02 could not be marked complete.
- **Resolution plan:** Make verification use a pinned containerized Helm fallback,
  add the missing Redis PDB assertion, and rerun static plus Redis runtime checks.

### Resolution

- Added a Redis `PodDisruptionBudget` with `minAvailable: 1`.
- Added a pinned `alpine/helm:3.14.4` fallback for environments without Helm.
- Removed the source-file fallback that incorrectly described unrendered templates
  as rendered manifests.
- **Verification:** `make k8s-redis-verify` passed manifest rendering, Secret,
  non-root, PVC, PDB, NetworkPolicy, authentication, TTL storage, and AOF restart
  persistence checks.
- **Status:** RESOLVED.
