#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART_DIR="${ROOT_DIR}/performance/helm/hyperscale-commerce"

echo "=== [VERIFY-K8S-SECURITY] Auditing Kubernetes Security & Network Policies ==="

# 1. NetworkPolicies Audit
echo "Auditing NetworkPolicies..."
helm template hyperscale-commerce "${CHART_DIR}" -s templates/networkpolicies.yaml >/dev/null
network_policy_manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s templates/networkpolicies.yaml)"
if ! grep -q "name: default-deny-all" <<<"${network_policy_manifest}"; then
  echo "ERROR: NetworkPolicy default-deny-all is missing!" >&2
  exit 1
fi
echo "  -> Default-deny-all NetworkPolicy verified (PASSED)"

# 2. ServiceAccount & Token Auto-Mount Audit
echo "Auditing ServiceAccount least-privilege tokens..."
service_account_manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s templates/serviceaccount.yaml)"
if ! grep -q "automountServiceAccountToken: false" <<<"${service_account_manifest}"; then
  echo "ERROR: ServiceAccount must disable automountServiceAccountToken!" >&2
  exit 1
fi
echo "  -> Least-privilege ServiceAccount verified (PASSED)"

# 3. Pod Security Standards
echo "Auditing Pod Security Standards enforcement..."
namespace_manifest="$(helm template hyperscale-commerce "${CHART_DIR}" -s templates/namespace.yaml)"
if ! grep -q "pod-security.kubernetes.io/enforce: restricted" <<<"${namespace_manifest}"; then
  echo "ERROR: Namespace must enforce restricted Pod Security Standard!" >&2
  exit 1
fi
echo "  -> Namespace restricted pod-security standard verified (PASSED)"

echo "=== [VERIFY-K8S-SECURITY] Kubernetes Security & Network Isolation Verified PASSED ==="
