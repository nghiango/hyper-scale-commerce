#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

SCENARIO="${1:-smoke}"
RESET_DATA="${RESET_DATA:-true}"
TIMESTAMP=$(date -u +"%Y%m%d-%H%M%SZ")
OUTPUT_DIR="${ROOT_DIR}/build/performance-results/${SCENARIO}-${TIMESTAMP}"
mkdir -p "${OUTPUT_DIR}"

# Create/update 'current' pointer
rm -rf "${ROOT_DIR}/build/performance-results/current"
ln -s "${OUTPUT_DIR}" "${ROOT_DIR}/build/performance-results/current"

echo "================================================================="
echo "  HyperScale Commerce — Load Scenario Runner: ${SCENARIO}"
echo "  Output Directory: ${OUTPUT_DIR}"
echo "================================================================="

# 1. Preflight checks
bash "${SCRIPT_DIR}/preflight.sh"

# 2. Reset order transactional data if requested
if [ "${RESET_DATA}" = "true" ]; then
  bash "${SCRIPT_DIR}/reset-order-data.sh"
fi

# 3. Seed catalog reference data
bash "${SCRIPT_DIR}/seed-data.sh" 1000

# 4. Capture environment metadata
COMMIT_SHA=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
DIRTY_STATE=$(git status --porcelain 2>/dev/null | grep -q . && echo "dirty" || echo "clean")
HOST_CPU=$(uname -m)
HOST_OS=$(uname -s -r)

cat <<EOF > "${OUTPUT_DIR}/environment.json"
{
  "scenario": "${SCENARIO}",
  "timestamp": "${TIMESTAMP}",
  "commitSha": "${COMMIT_SHA}",
  "dirtyWorktree": "${DIRTY_STATE}",
  "hostOs": "${HOST_OS}",
  "hostCpu": "${HOST_CPU}",
  "k6Image": "grafana/k6:0.57.0@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b"
}
EOF

# 5. Pre-run metrics snapshot
bash "${SCRIPT_DIR}/snapshot-metrics.sh" before "${OUTPUT_DIR}"

# 6. Run k6 container
echo "=== [K6 RUN] Starting k6 execution for scenario: ${SCENARIO} ==="
K6_SCRIPT="/scripts/${SCENARIO}.js"

docker compose -f "${ROOT_DIR}/compose.yaml" -f "${ROOT_DIR}/performance/compose.load.yml" \
  --profile load run --rm \
  -e APP_BASE_URL="http://app:8080" \
  -e ORDER_QUERY_BASE_URL="http://order-query:8081" \
  -e PRNG_SEED="42" \
  -e WORKLOAD="${WORKLOAD:-mixed}" \
  -e BASELINE_STEP_DURATION="${BASELINE_STEP_DURATION:-45s}" \
  -e QUAL_TARGET_VUS="${QUAL_TARGET_VUS:-10000}" \
  -e QUAL_RAMP_UP="${QUAL_RAMP_UP:-1m}" \
  -e QUAL_STEADY_STATE="${QUAL_STEADY_STATE:-2m}" \
  -e QUAL_RAMP_DOWN="${QUAL_RAMP_DOWN:-30s}" \
  -e SPIKE_1X_RATE="${SPIKE_1X_RATE:-500}" \
  -e SPIKE_5X_RATE="${SPIKE_5X_RATE:-2500}" \
  -e SPIKE_STAGE1="${SPIKE_STAGE1:-1m}" \
  -e SPIKE_RAMP_UP="${SPIKE_RAMP_UP:-15s}" \
  -e SPIKE_BURST="${SPIKE_BURST:-1m}" \
  -e SPIKE_RAMP_DOWN="${SPIKE_RAMP_DOWN:-15s}" \
  -e SPIKE_RECOVERY="${SPIKE_RECOVERY:-2m}" \
  k6 run "${K6_SCRIPT}" \
  --summary-export "/results/${SCENARIO}-${TIMESTAMP}-summary.json" 2>&1 | tee "${OUTPUT_DIR}/k6.log"

K6_EXIT_CODE="${PIPESTATUS[0]}"

# Move generated summary to output directory
if [ -f "${ROOT_DIR}/build/performance-results/${SCENARIO}-${TIMESTAMP}-summary.json" ]; then
  mv "${ROOT_DIR}/build/performance-results/${SCENARIO}-${TIMESTAMP}-summary.json" "${OUTPUT_DIR}/k6-summary.json"
fi

# 7. Post-run metrics snapshot
bash "${SCRIPT_DIR}/snapshot-metrics.sh" after "${OUTPUT_DIR}"

# 8. Asynchronous drain period
echo "=== [DRAIN] Waiting for in-flight outbox/Kafka/projection drain ==="
MAX_DRAIN_SECONDS=60
DRAIN_START=$(date +%s)
while true; do
  UNPUBLISHED=$(docker exec -i hyperscale-postgres psql -U hyperscale -d hyperscale -t -A -c "SELECT count(*) FROM \"order\".outbox_events WHERE published_at IS NULL;" 2>/dev/null || echo "0")
  if [ "${UNPUBLISHED}" -eq 0 ]; then
    echo "=== [DRAIN] All outbox events published (took $(( $(date +%s) - DRAIN_START ))s), waiting for consumer commit ==="
    sleep 6
    break
  fi
  NOW=$(date +%s)
  ELAPSED=$((NOW - DRAIN_START))
  if [ "${ELAPSED}" -ge "${MAX_DRAIN_SECONDS}" ]; then
    echo "=== [DRAIN] Warning: drain timeout reached (${UNPUBLISHED} outbox events remaining) ==="
    break
  fi
  sleep 2
done

# 9. Post-test data reconciliation
RECON_EXIT_CODE=0
bash "${SCRIPT_DIR}/reconcile-data.sh" "${OUTPUT_DIR}" || RECON_EXIT_CODE=$?

echo "================================================================="
if [ "${K6_EXIT_CODE}" -eq 0 ] && [ "${RECON_EXIT_CODE}" -eq 0 ]; then
  echo "  SUCCESS: Scenario ${SCENARIO} completed with all thresholds and reconciliation PASSING."
  echo "  Results stored in: ${OUTPUT_DIR}"
  echo "================================================================="
  exit 0
else
  echo "  FAILURE: Scenario ${SCENARIO} failed (k6_exit=${K6_EXIT_CODE}, recon_exit=${RECON_EXIT_CODE})." >&2
  echo "  Results stored in: ${OUTPUT_DIR}" >&2
  echo "================================================================="
  exit 1
fi
