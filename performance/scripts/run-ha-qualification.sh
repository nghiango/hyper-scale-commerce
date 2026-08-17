#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [HA-QUALIFICATION] Starting Phase 14 High Availability Qualification Suite ==="

RESULTS_DIR="${ROOT_DIR}/build/performance-results/ha-qualification"
mkdir -p "${RESULTS_DIR}"

# 1. Preflight
echo "Step 1: Running HA Ingress and Kafka Preflight Checks..."
bash "${SCRIPT_DIR}/preflight-ingress.sh"
bash "${SCRIPT_DIR}/preflight-kafka-ha.sh"

# 2. Reset Data
echo "Step 2: Resetting database order/inventory tables..."
bash "${SCRIPT_DIR}/reset-order-data.sh"

# 3. Seed Catalog
echo "Step 3: Ensuring Catalog is seeded..."
bash "${SCRIPT_DIR}/seed-data.sh"

# 4. Run Steady-State HA Ingress Load Test
echo "Step 4: Running HA Ingress No-Fault Steady State Test..."
K6_IMAGE="grafana/k6:0.57.0@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b"

docker run --rm \
  --network host \
  -v "${ROOT_DIR}/performance/k6:/scripts:ro" \
  -v "${RESULTS_DIR}:/results" \
  -e APP_BASE_URL="http://127.0.0.1:8080" \
  -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
  -e HA_TARGET_VUS="100" \
  -e HA_STEADY_STATE="30s" \
  "${K6_IMAGE}" run --summary-export=/results/ha-baseline-summary.json /scripts/ha-qualification.js

# 5. Run HA Chaos Replica and Broker Failover Tests
echo "Step 5: Running HA Application Replica Failover Test under Load..."
bash "${ROOT_DIR}/performance/chaos/run-ha-chaos.sh" app-replica-loss

echo "Step 6: Running HA Kafka Active Leader Loss Test under Load..."
bash "${ROOT_DIR}/performance/chaos/run-ha-chaos.sh" kafka-leader-loss

# 6. Snapshot Prometheus Metrics
echo "Step 7: Capturing Prometheus metrics snapshot..."
bash "${SCRIPT_DIR}/snapshot-metrics.sh" "${RESULTS_DIR}/metrics-snapshot.txt" || true

# 7. Asynchronous Drain
echo "Step 8: Allowing 10 seconds asynchronous drain for Kafka outbox and consumer groups..."
sleep 10

# 8. Final SQL Data Reconciliation
echo "Step 9: Running final cross-schema data reconciliation..."
bash "${SCRIPT_DIR}/reconcile-data.sh"

echo "=== [HA-QUALIFICATION] Phase 14 High Availability Qualification Suite PASSED ==="
