#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="hyperscale-k8s"

echo "=== [K8S-CLUSTER-DOWN] Deleting Multi-Node Kubernetes (kind) Cluster: ${CLUSTER_NAME} ==="

if ! command -v kind >/dev/null 2>&1; then
  echo "kind binary not found, skipping."
  exit 0
fi

if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "Deleting kind cluster '${CLUSTER_NAME}'..."
  kind delete cluster --name "${CLUSTER_NAME}"
  echo "Cluster '${CLUSTER_NAME}' deleted successfully."
else
  echo "Cluster '${CLUSTER_NAME}' does not exist. Nothing to clean up."
fi

echo "=== [K8S-CLUSTER-DOWN] Cleaned up ${CLUSTER_NAME} ==="
