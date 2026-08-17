#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART_DIR="${ROOT_DIR}/performance/helm/hyperscale-commerce"
CHART_RELATIVE="performance/helm/hyperscale-commerce"
HELM_IMAGE="alpine/helm:3.14.4"

echo "=== [VERIFY-K8S-REDIS] Auditing Redis L2 Cache Kubernetes Packaging ==="

# Render through the host Helm binary when available, otherwise use the pinned
# test-only container so verification does not depend on workstation tooling.
run_helm() {
  if command -v helm >/dev/null 2>&1; then
    helm "$@"
    return
  fi

  if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: Helm verification requires either helm or docker." >&2
    exit 1
  fi

  docker run --rm \
    --volume "${ROOT_DIR}:/workspace:ro" \
    --workdir /workspace \
    "${HELM_IMAGE}" "$@"
}

get_manifest() {
  local template="$1"
  local chart="${CHART_DIR}"
  if ! command -v helm >/dev/null 2>&1; then
    chart="${CHART_RELATIVE}"
  fi
  run_helm template hyperscale-commerce "${chart}" -s "${template}"
}

# 1. Render & Validate Manifests
echo "Rendering and validating Redis Helm templates..."
redis_manifest="$(get_manifest "templates/redis.yaml")"
echo "  -> Redis template rendered cleanly (PASSED)"

# 2. Invariant Audit: Authentication & Secret
echo "Auditing Redis authentication secret..."
if ! grep -q "kind: Secret" <<<"${redis_manifest}"; then
  echo "ERROR: Redis Secret is missing!" >&2
  exit 1
fi
echo "  -> Redis Secret verified (PASSED)"

# 3. Invariant Audit: Non-root Security Context
echo "Auditing Redis non-root security context..."
if ! grep -q "runAsNonRoot: true" <<<"${redis_manifest}"; then
  echo "ERROR: Redis must run as non-root user!" >&2
  exit 1
fi
echo "  -> Redis non-root security verified (PASSED)"

# 4. Invariant Audit: Storage PVC
echo "Auditing Redis persistent volume claim templates..."
if ! grep -q "volumeClaimTemplates:" <<<"${redis_manifest}"; then
  echo "ERROR: Redis missing volumeClaimTemplates for append-only log!" >&2
  exit 1
fi
echo "  -> Redis volumeClaimTemplates verified (PASSED)"

# 5. Invariant Audit: PodDisruptionBudget
echo "Auditing Redis PodDisruptionBudget..."
if ! grep -q "name: redis-pdb" <<<"${redis_manifest}"; then
  echo "ERROR: Redis PodDisruptionBudget is missing!" >&2
  exit 1
fi
echo "  -> Redis PodDisruptionBudget verified (PASSED)"

# 6. Invariant Audit: NetworkPolicy Isolation
echo "Auditing Redis NetworkPolicy ingress whitelist..."
network_policy_manifest="$(get_manifest "templates/networkpolicies.yaml")"
if ! grep -q "name: allow-app-to-redis" <<<"${network_policy_manifest}"; then
  echo "ERROR: Missing allow-app-to-redis NetworkPolicy!" >&2
  exit 1
fi
echo "  -> Redis NetworkPolicy whitelist verified (PASSED)"

# 7. Prometheus exporter wiring
echo "Auditing Redis Prometheus exporter wiring..."
for expected in "name: redis-exporter" "containerPort: 9121" "prometheus.io/scrape: \"true\""; do
  if ! grep -q "${expected}" <<<"${redis_manifest}"; then
    echo "ERROR: Redis exporter invariant is missing: ${expected}" >&2
    exit 1
  fi
done
if ! grep -q "port: 9121" <<<"${network_policy_manifest}"; then
  echo "ERROR: Redis metrics port is not admitted by NetworkPolicy." >&2
  exit 1
fi
echo "  -> Redis Prometheus exporter verified (PASSED)"

# 8. Application wiring
echo "Auditing application Redis connection wiring..."
for template in templates/app.yaml templates/order-query.yaml; do
  manifest="$(get_manifest "${template}")"
  if ! grep -q "name: SPRING_DATA_REDIS_HOST" <<<"${manifest}"; then
    echo "ERROR: ${template} is missing the Redis host configuration." >&2
    exit 1
  fi
  if ! grep -q "name: SPRING_DATA_REDIS_PASSWORD" <<<"${manifest}"; then
    echo "ERROR: ${template} is missing the Redis password Secret reference." >&2
    exit 1
  fi
done
echo "  -> App and Order Query Redis wiring verified (PASSED)"

# 9. Runtime Verification: Container Auth, Persistence & Non-Root Execution
if docker info >/dev/null 2>&1; then
  echo "[Step 9] Running Redis container runtime, auth & persistence tests..."
  bash "${ROOT_DIR}/performance/scripts/test-redis-runtime.sh"
else
  echo "ERROR: Docker is required for Redis runtime verification." >&2
  exit 1
fi

echo "=== [VERIFY-K8S-REDIS] Redis L2 Cache Packaging & Runtime Verified PASSED ==="
