#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-hyperscale-k8s}"
CONTEXT="${KUBE_CONTEXT:-kind-${CLUSTER_NAME}}"

echo "=== [PREFLIGHT-K8S] Checking Multi-Node Kubernetes (kind) Cluster State ==="

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: 'kubectl' binary not found." >&2
  exit 1
fi

echo "Checking cluster info..."
kubectl cluster-info --context "${CONTEXT}"

echo "Checking node count and status..."
READY_NODES=$(kubectl --context "${CONTEXT}" get nodes --no-headers | grep -c " Ready" || true)
TOTAL_NODES=$(kubectl --context "${CONTEXT}" get nodes --no-headers | wc -l | tr -d ' ')

echo "  - Total Nodes: ${TOTAL_NODES} (Expect 6: 3 control-plane, 3 workers)"
echo "  - Ready Nodes: ${READY_NODES} (Expect 6)"

if [ "${READY_NODES}" -lt 6 ]; then
  echo "ERROR: Expected 6 Ready nodes, found ${READY_NODES}." >&2
  exit 1
fi

echo "Checking CoreDNS pods..."
COREDNS_READY=$(kubectl --context "${CONTEXT}" get pods -n kube-system -l k8s-app=kube-dns --field-selector=status.phase=Running --no-headers | wc -l | tr -d ' ')
echo "  - Running CoreDNS pods: ${COREDNS_READY}"

echo "Checking StorageClass..."
SC_COUNT=$(kubectl --context "${CONTEXT}" get storageclass --no-headers | wc -l | tr -d ' ')
echo "  - Available StorageClasses: ${SC_COUNT}"
if [ "${SC_COUNT}" -eq 0 ]; then
  echo "ERROR: No StorageClass found in cluster." >&2
  exit 1
fi

echo "=== [PREFLIGHT-K8S] Kubernetes Multi-Node Cluster Preflight PASSED ==="
