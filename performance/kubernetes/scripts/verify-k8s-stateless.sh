#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART_DIR="${ROOT_DIR}/performance/helm/hyperscale-commerce"

echo "=== [VERIFY-K8S-STATELESS] Verifying Replicated Ingress & Application Deployments ==="

# 1. Render & Validate Manifests
echo "Rendering and validating Helm stateless manifests..."
helm template hyperscale-commerce "${CHART_DIR}" -s templates/ingress.yaml >/dev/null
helm template hyperscale-commerce "${CHART_DIR}" -s templates/app.yaml >/dev/null
helm template hyperscale-commerce "${CHART_DIR}" -s templates/order-query.yaml >/dev/null
echo "  -> Stateless manifest templates rendered cleanly (PASSED)"

# 2. Invariant Audit: Zero-Downtime Rolling Update Strategy
echo "Auditing rolling update strategies for app and order-query..."
for component in app order-query; do
  manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s "templates/${component}.yaml")"
  max_unavail="$(awk '/maxUnavailable:/ { gsub(/[[:space:]]/, "", $0); split($0, value, ":"); print value[2]; exit }' <<<"${manifest}")"
  max_surge="$(awk '/maxSurge:/ { gsub(/[[:space:]]/, "", $0); split($0, value, ":"); print value[2]; exit }' <<<"${manifest}")"
  echo "  - ${component}: maxUnavailable=${max_unavail}, maxSurge=${max_surge}"
  if [ "${max_unavail}" -ne 0 ] || [ "${max_surge}" -ne 1 ]; then
    echo "ERROR: ${component} rolling update must enforce maxUnavailable=0 and maxSurge=1!" >&2
    exit 1
  fi
done

# 3. Invariant Audit: HAProxy Peer Stick-Table Synchronization
echo "Auditing HAProxy ingress peer synchronization..."
ingress_manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s templates/ingress.yaml)"
if ! grep -q "peers mypeers" <<<"${ingress_manifest}"; then
  echo "ERROR: HAProxy ingress missing peer stick-table sync configuration!" >&2
  exit 1
fi
echo "  -> HAProxy peer stick-table synchronization verified (PASSED)"

# 4. Invariant Audit: PodDisruptionBudgets
echo "Auditing stateless PodDisruptionBudgets..."
for component in app order-query ingress; do
  manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s "templates/${component}.yaml")"
  if ! grep -q "kind: PodDisruptionBudget" <<<"${manifest}"; then
    echo "ERROR: ${component} missing PodDisruptionBudget!" >&2
    exit 1
  fi
  echo "  - ${component} PDB present (PASSED)"
done

# 5. Invariant Audit: Security Context
echo "Auditing container non-root security contexts..."
for component in app order-query; do
  manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s "templates/${component}.yaml")"
  if ! grep -q "runAsNonRoot: true" <<<"${manifest}"; then
    echo "ERROR: ${component} missing runAsNonRoot: true!" >&2
    exit 1
  fi
  echo "  - ${component} runAsNonRoot verified (PASSED)"
done

echo "=== [VERIFY-K8S-STATELESS] Replicated Ingress & Stateless Workloads Verified PASSED ==="
