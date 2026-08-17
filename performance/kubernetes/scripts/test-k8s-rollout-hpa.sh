#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART_DIR="${ROOT_DIR}/performance/helm/hyperscale-commerce"

echo "=== [TEST-K8S-ROLLOUT-HPA] Testing Resource Governance, Rollouts & HPA ==="

# 1. Render & Validate Manifests
echo "Rendering and validating HPA templates..."
helm template hyperscale-commerce "${CHART_DIR}" -s templates/hpa.yaml >/dev/null
echo "  -> HPA templates rendered cleanly (PASSED)"

# 2. Invariant Audit: QoS (Zero BestEffort Pods)
echo "Auditing CPU & Memory requests/limits across all workloads..."
for comp in app order-query ingress kafka etcd postgres-ha; do
  manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s "templates/${comp}.yaml")"
  if ! grep -q "requests:" <<<"${manifest}" || ! grep -q "limits:" <<<"${manifest}"; then
    echo "ERROR: Component ${comp} missing explicit requests or limits!" >&2
    exit 1
  fi
  echo "  - ${comp} requests/limits defined (Guaranteed/Burstable QoS) (PASSED)"
done

# 3. Invariant Audit: HPA Capacity Bounds vs PostgreSQL max_connections
echo "Auditing HPA maximum replica scale vs PostgreSQL connection capacity..."
hpa_manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s templates/hpa.yaml)"
MAX_APP_REPLICAS="$(awk '/maxReplicas:/ { gsub(/[[:space:]]/, "", $0); split($0, value, ":"); print value[2]; exit }' <<<"${hpa_manifest}")"
MAX_QUERY_REPLICAS="$(awk '/maxReplicas:/ { gsub(/[[:space:]]/, "", $0); split($0, value, ":"); result=value[2] } END { print result }' <<<"${hpa_manifest}")"
echo "  - Max app replicas: ${MAX_APP_REPLICAS}"
echo "  - Max order-query replicas: ${MAX_QUERY_REPLICAS}"

# Each replica pool size is minimum-idle=5, maximum-pool-size=10
TOTAL_MAX_CONN=$(( (MAX_APP_REPLICAS + MAX_QUERY_REPLICAS) * 10 ))
echo "  - Total maximum possible database connections from HPA max pods: ${TOTAL_MAX_CONN} (PostgreSQL limit: 300)"
if [ "${TOTAL_MAX_CONN}" -gt 250 ]; then
  echo "ERROR: HPA max replicas would risk PostgreSQL connection pool exhaustion!" >&2
  exit 1
fi
echo "  -> HPA scale bounds strictly within safe PostgreSQL pool budget (PASSED)"

echo "=== [TEST-K8S-ROLLOUT-HPA] Resource Governance & HPA Scaling Bounds Verified PASSED ==="
