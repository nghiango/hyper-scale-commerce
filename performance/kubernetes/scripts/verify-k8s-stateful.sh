#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART_DIR="${ROOT_DIR}/performance/helm/hyperscale-commerce"

echo "=== [VERIFY-K8S-STATEFUL] Verifying Stateful Quorums & Storage Packaging ==="

# 1. Render & Validate Helm Templates
echo "Rendering and validating Helm stateful manifests..."
helm template hyperscale-commerce "${CHART_DIR}" -s templates/etcd.yaml >/dev/null
helm template hyperscale-commerce "${CHART_DIR}" -s templates/kafka.yaml >/dev/null
helm template hyperscale-commerce "${CHART_DIR}" -s templates/postgres-ha.yaml >/dev/null
helm template hyperscale-commerce "${CHART_DIR}" -s templates/pgbackrest-backup.yaml >/dev/null
echo "  -> Stateful manifest templates rendered cleanly (PASSED)"

# 2. Invariant Audit: PodDisruptionBudgets
echo "Verifying PodDisruptionBudget configurations..."
for component in etcd kafka postgres-ha; do
  manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s "templates/${component}.yaml")"
  min_avail="$(awk '/kind: PodDisruptionBudget/ { pdb=1 } pdb && /minAvailable:/ { gsub(/[[:space:]]/, "", $0); split($0, value, ":"); print value[2]; exit }' <<<"${manifest}")"
  echo "  - ${component} PDB minAvailable: ${min_avail} (Expect 2)"
  if [ "${min_avail}" -ne 2 ]; then
    echo "ERROR: ${component} PDB minAvailable should be 2 to protect quorum!" >&2
    exit 1
  fi
done

# 3. Invariant Audit: Anti-Affinity
echo "Verifying Pod Anti-Affinity rules..."
for component in etcd kafka postgres-ha; do
  manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s "templates/${component}.yaml")"
  if ! grep -q "podAntiAffinity" <<<"${manifest}"; then
    echo "ERROR: ${component} missing podAntiAffinity rule!" >&2
    exit 1
  fi
  echo "  - ${component} podAntiAffinity present (PASSED)"
done

# 4. Invariant Audit: VolumeClaimTemplates
echo "Verifying persistent volume claim templates..."
for component in etcd kafka postgres-ha; do
  manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s "templates/${component}.yaml")"
  if ! grep -q "volumeClaimTemplates:" <<<"${manifest}"; then
    echo "ERROR: ${component} missing volumeClaimTemplates!" >&2
    exit 1
  fi
  echo "  - ${component} volumeClaimTemplates present (PASSED)"
done

echo "=== [VERIFY-K8S-STATEFUL] Stateful Quorum Workloads Packaging Verified PASSED ==="
