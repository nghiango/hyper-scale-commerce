#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CLUSTER_NAME="hyperscale-k8s"
KIND_CONFIG="${ROOT_DIR}/performance/kubernetes/kind-config.yaml"

echo "=== [K8S-CLUSTER-UP] Starting Multi-Node Kubernetes (kind) Cluster: ${CLUSTER_NAME} ==="

if ! command -v kind >/dev/null 2>&1; then
  echo "ERROR: 'kind' binary not found. Please install kind (https://kind.sigs.k8s.io/docs/user/quick-start/)." >&2
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: 'kubectl' binary not found. Please install kubectl." >&2
  exit 1
fi

# Check if cluster already exists
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "Cluster '${CLUSTER_NAME}' already exists. Setting kubectl context..."
  kubectl cluster-info --context "kind-${CLUSTER_NAME}"
else
  echo "Creating kind cluster '${CLUSTER_NAME}' from ${KIND_CONFIG}..."
  kind create cluster --name "${CLUSTER_NAME}" --config "${KIND_CONFIG}" --wait 120s
fi

echo "Waiting for all 6 nodes (3 control-plane, 3 workers) to become Ready..."
kubectl wait --for=condition=Ready nodes --all --timeout=120s

echo "Creating 'hyperscale' namespace..."
kubectl create namespace hyperscale --dry-run=client -o yaml | kubectl apply -f -
kubectl label namespace hyperscale \
  app.kubernetes.io/name=hyperscale-commerce \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted \
  --overwrite

echo "=== [K8S-CLUSTER-UP] Multi-Node Kubernetes cluster ${CLUSTER_NAME} is READY ==="
