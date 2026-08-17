#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-kind-hyperscale-k8s}"
NAMESPACE="${KUBE_NAMESPACE:-hyperscale}"
JOB_NAME="phase17-cache-qualification"
CONFIG_NAME="phase17-k6-script"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.57.0}"
PEAK_VUS="${PEAK_VUS:-5000}"
WARM_VUS="${WARM_VUS:-500}"
RAMP_DURATION="${RAMP_DURATION:-60s}"
STEADY_DURATION="${STEADY_DURATION:-120s}"
COOLDOWN_DURATION="${COOLDOWN_DURATION:-30s}"
PACING_SECONDS="${PACING_SECONDS:-1.5}"
FAULT_DELAY_SECONDS="${FAULT_DELAY_SECONDS:-135}"
REDIS_OUTAGE_SECONDS="${REDIS_OUTAGE_SECONDS:-10}"
REPLICA_LAG_SECONDS="${REPLICA_LAG_SECONDS:-10}"
FAULT_RECOVERY_SECONDS="${FAULT_RECOVERY_SECONDS:-15}"
FAULT_WINDOW_START_SECONDS="${FAULT_WINDOW_START_SECONDS:-125}"
FAULT_WINDOW_END_SECONDS="${FAULT_WINDOW_END_SECONDS:-180}"
RESULTS_DIR="${RESULTS_DIR:-${ROOT_DIR}/build/qualification-results/cache}"
EVIDENCE_FILE="${ROOT_DIR}/docs/bootcamp/evidence/p17-cache-replica-scaling.md"
K6_LOG="${RESULTS_DIR}/k6.log"
CPU_LOG="${RESULTS_DIR}/primary-cpu-percent.txt"
BASELINE_CPU_LOG="${RESULTS_DIR}/primary-cpu-baseline-percent.txt"
FAULT_CPU_LOG="${RESULTS_DIR}/primary-cpu-fault-percent.txt"
FAULT_MARKER="${RESULTS_DIR}/fault-active"
LAG_LOG="${RESULTS_DIR}/replica-lag-seconds.txt"
FAULT_LOG="${RESULTS_DIR}/faults.log"

mkdir -p "${RESULTS_DIR}"
: >"${CPU_LOG}"
: >"${BASELINE_CPU_LOG}"
: >"${FAULT_CPU_LOG}"
: >"${LAG_LOG}"
: >"${FAULT_LOG}"
rm -f "${FAULT_MARKER}"

REPLICA_PODS=""

cleanup() {
  rm -f "${FAULT_MARKER}"
  for pod in ${REPLICA_PODS}; do
    kubectl --context "${CONTEXT}" -n "${NAMESPACE}" exec "${pod}" -- \
      psql -U hyperscale -d hyperscale -c "SELECT pg_wal_replay_resume();" >/dev/null 2>&1 || true
  done
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" delete job "${JOB_NAME}" \
    --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" delete configmap "${CONFIG_NAME}" \
    --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: Required command '$1' is unavailable." >&2
    exit 1
  fi
}

kube() {
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" "$@"
}

patroni_role() {
  kube exec "$1" -- curl -fsS http://localhost:8008/patroni | jq -r '.role // empty'
}

discover_database_pods() {
  local pod role
  PRIMARY_POD=""
  REPLICA_PODS=""
  for pod in $(kube get pods -l app.kubernetes.io/component=postgres-ha -o jsonpath='{.items[*].metadata.name}'); do
    role="$(patroni_role "${pod}")"
    case "${role}" in
      primary|master) PRIMARY_POD="${pod}" ;;
      replica|sync_standby) REPLICA_PODS="${REPLICA_PODS} ${pod}" ;;
    esac
  done
  if [ -z "${PRIMARY_POD}" ] || [ "$(wc -w <<<"${REPLICA_PODS}")" -lt 2 ]; then
    echo "ERROR: Qualification requires one Patroni primary and at least two standbys." >&2
    exit 1
  fi
}

query_primary() {
  kube exec "${PRIMARY_POD}" -- psql -U hyperscale -d hyperscale -t -A -c "$1"
}

sample_primary_cpu() {
  local first second elapsed delta percent started complete failed
  while :; do
    first="$(kube exec "${PRIMARY_POD}" -- awk '/usage_usec/ {print $2}' /sys/fs/cgroup/cpu.stat)"
    started="$(date +%s)"
    sleep 5
    second="$(kube exec "${PRIMARY_POD}" -- awk '/usage_usec/ {print $2}' /sys/fs/cgroup/cpu.stat)"
    elapsed=$(( $(date +%s) - started ))
    delta=$(( second - first ))
    percent="$(awk -v delta="${delta}" -v elapsed="${elapsed}" 'BEGIN { printf "%.3f", delta / (elapsed * 1000000) * 100 }')"
    echo "${percent}" >>"${CPU_LOG}"
    if [ -e "${FAULT_MARKER}" ]; then
      echo "${percent}" >>"${FAULT_CPU_LOG}"
    else
      echo "${percent}" >>"${BASELINE_CPU_LOG}"
    fi
    complete="$(kube get job "${JOB_NAME}" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)"
    failed="$(kube get job "${JOB_NAME}" -o jsonpath='{.status.failed}' 2>/dev/null || true)"
    if [ "${complete:-0}" -gt 0 ] || [ "${failed:-0}" -gt 0 ]; then return; fi
  done
}

sample_application_lag() {
  local pod metrics
  for pod in $(kube get pods -l app.kubernetes.io/component=app -o jsonpath='{.items[*].metadata.name}'); do
    metrics="$(kube exec "${pod}" -- curl -fsS http://127.0.0.1:8080/actuator/prometheus)"
    awk '/^postgres_replication_lag_seconds\{/ {print $2}' <<<"${metrics}" >>"${LAG_LOG}"
  done
}

inject_faults() {
  sleep "${FAULT_DELAY_SECONDS}"
  touch "${FAULT_MARKER}"
  echo "$(date -u +%FT%TZ) deleting redis-0" | tee -a "${FAULT_LOG}"
  kube delete pod redis-0 --wait=false
  sleep "${REDIS_OUTAGE_SECONDS}"
  kube wait --for=condition=Ready pod/redis-0 --timeout=120s
  echo "$(date -u +%FT%TZ) redis-0 recovered" | tee -a "${FAULT_LOG}"

  local pod
  for pod in ${REPLICA_PODS}; do
    kube exec "${pod}" -- psql -U hyperscale -d hyperscale -c "SELECT pg_wal_replay_pause();"
  done
  echo "$(date -u +%FT%TZ) standby replay paused" | tee -a "${FAULT_LOG}"
  query_primary 'SELECT pg_switch_wal();' >/dev/null
  local observed_lag attempt
  for attempt in $(seq 1 "${REPLICA_LAG_SECONDS}"); do
    sleep 1
    sample_application_lag
    observed_lag="$(sort -nr "${LAG_LOG}" | head -1)"
    if awk -v value="${observed_lag:-0}" 'BEGIN { exit !(value > 0.1) }'; then break; fi
  done
  for pod in ${REPLICA_PODS}; do
    kube exec "${pod}" -- psql -U hyperscale -d hyperscale -c "SELECT pg_wal_replay_resume();"
  done
  echo "$(date -u +%FT%TZ) standby replay resumed" | tee -a "${FAULT_LOG}"
  sleep "${FAULT_RECOVERY_SECONDS}"
  rm -f "${FAULT_MARKER}"
}

wait_for_job() {
  local succeeded failed
  while :; do
    succeeded="$(kube get job "${JOB_NAME}" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)"
    failed="$(kube get job "${JOB_NAME}" -o jsonpath='{.status.failed}' 2>/dev/null || true)"
    if [ "${succeeded:-0}" -gt 0 ]; then return 0; fi
    if [ "${failed:-0}" -gt 0 ]; then return 1; fi
    sleep 5
  done
}

echo "=== [CACHE-REPLICA-QUALIFICATION] Phase 17 empirical qualification ==="
for command in kubectl jq awk curl; do require_command "${command}"; done
if [ "${PEAK_VUS}" -lt 5000 ]; then
  echo "ERROR: PEAK_VUS=${PEAK_VUS}; Phase 17 requires at least 5000." >&2
  exit 1
fi

kubectl cluster-info --context "${CONTEXT}" >/dev/null
KUBE_CONTEXT="${CONTEXT}" bash "${SCRIPT_DIR}/preflight-cluster.sh"
bash "${SCRIPT_DIR}/verify-k8s-redis.sh"

for workload in deployment/app deployment/order-query statefulset/redis statefulset/postgres-ha statefulset/kafka; do
  kube rollout status "${workload}" --timeout=180s
done
APP_POD="$(kube get pods -l app.kubernetes.io/component=app -o jsonpath='{.items[0].metadata.name}')"
QUERY_POD="$(kube get pods -l app.kubernetes.io/component=order-query -o jsonpath='{.items[0].metadata.name}')"
kube exec "${APP_POD}" -- curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null
kube exec "${QUERY_POD}" -- curl -fsS http://127.0.0.1:8081/actuator/health/readiness >/dev/null

discover_database_pods
echo "Resetting transactional data on ${PRIMARY_POD}..."
query_primary 'TRUNCATE TABLE "order".order_items, "order".orders, "order".outbox_events, inventory.reservations, order_query.order_read_model CASCADE;' >/dev/null

kube create configmap "${CONFIG_NAME}" \
  --from-file=cache-replica-qualification.js="${ROOT_DIR}/performance/k6/cache-replica-qualification.js" \
  --dry-run=client -o yaml | kube apply -f -
kube delete job "${JOB_NAME}" --ignore-not-found >/dev/null

cat <<EOF | kube apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: ${JOB_NAME}
spec:
  backoffLimit: 0
  template:
    metadata:
      labels:
        app.kubernetes.io/name: hyperscale-commerce
        app.kubernetes.io/component: load-generator
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 12345
        runAsGroup: 12345
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: k6
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
          image: ${K6_IMAGE}
          args: ["run", "/scripts/cache-replica-qualification.js"]
          env:
            - { name: TARGET_URL, value: "http://app-service.${NAMESPACE}.svc.cluster.local:8080" }
            - { name: QUERY_URL, value: "http://order-query-service.${NAMESPACE}.svc.cluster.local:8081" }
            - { name: PEAK_VUS, value: "${PEAK_VUS}" }
            - { name: WARM_VUS, value: "${WARM_VUS}" }
            - { name: RAMP_DURATION, value: "${RAMP_DURATION}" }
            - { name: STEADY_DURATION, value: "${STEADY_DURATION}" }
            - { name: COOLDOWN_DURATION, value: "${COOLDOWN_DURATION}" }
            - { name: PACING_SECONDS, value: "${PACING_SECONDS}" }
            - { name: FAULT_WINDOW_START_SECONDS, value: "${FAULT_WINDOW_START_SECONDS}" }
            - { name: FAULT_WINDOW_END_SECONDS, value: "${FAULT_WINDOW_END_SECONDS}" }
          resources:
            requests: { cpu: "1000m", memory: "1Gi" }
            limits: { cpu: "4000m", memory: "4Gi" }
          volumeMounts:
            - { name: script, mountPath: /scripts, readOnly: true }
      volumes:
        - name: script
          configMap: { name: ${CONFIG_NAME} }
EOF

kube wait --for=condition=Ready pod -l job-name="${JOB_NAME}" --timeout=180s
sample_primary_cpu &
CPU_PID=$!
inject_faults &
FAULT_PID=$!

JOB_STATUS=0
wait_for_job || JOB_STATUS=$?
wait "${FAULT_PID}"
wait "${CPU_PID}"

K6_POD="$(kube get pods -l job-name="${JOB_NAME}" -o jsonpath='{.items[0].metadata.name}')"
kube logs "${K6_POD}" >"${K6_LOG}"
if [ "${JOB_STATUS}" -ne 0 ]; then
  echo "ERROR: k6 Job failed; see ${K6_LOG}." >&2
  exit 1
fi

SUMMARY_JSON="$(sed -n 's/^PHASE17_SUMMARY_JSON=//p' "${K6_LOG}" | tail -1)"
if [ -z "${SUMMARY_JSON}" ] || ! jq -e . >/dev/null <<<"${SUMMARY_JSON}"; then
  echo "ERROR: k6 summary JSON is absent or invalid." >&2
  exit 1
fi
printf '%s\n' "${SUMMARY_JSON}" >"${RESULTS_DIR}/k6-cache-summary.json"

echo "Waiting for outbox and projection drain..."
for _ in $(seq 1 60); do
  ORDERS_COUNT="$(query_primary 'SELECT COUNT(*) FROM "order".orders;')"
  OUTBOX_UNPUBLISHED="$(query_primary 'SELECT COUNT(*) FROM "order".outbox_events WHERE published_at IS NULL;')"
  READ_MODEL_COUNT="$(query_primary 'SELECT COUNT(*) FROM order_query.order_read_model;')"
  if [ "${OUTBOX_UNPUBLISHED}" -eq 0 ] && [ "${ORDERS_COUNT}" -eq "${READ_MODEL_COUNT}" ]; then break; fi
  sleep 2
done

ORDERS_PLACED="$(query_primary "SELECT COUNT(*) FROM \"order\".orders WHERE status = 'PLACED';")"
ORDERS_CANCELLED="$(query_primary "SELECT COUNT(*) FROM \"order\".orders WHERE status = 'CANCELLED';")"
RESERVATIONS="$(query_primary 'SELECT COUNT(*) FROM inventory.reservations;')"
READ_MODEL_DISTINCT="$(query_primary 'SELECT COUNT(DISTINCT order_id) FROM order_query.order_read_model;')"
READ_MODEL_CANCELLED="$(query_primary "SELECT COUNT(*) FROM order_query.order_read_model WHERE status = 'CANCELLED';")"

CATALOG_P95="$(jq -r '.metrics.cache_catalog_read_duration.values["p(95)"]' <<<"${SUMMARY_JSON}")"
QUERY_P95="$(jq -r '.metrics.replica_order_query_duration.values["p(95)"]' <<<"${SUMMARY_JSON}")"
FAULT_QUERY_P95="$(jq -r '.metrics.fault_order_query_duration.values["p(95)"] // 0' <<<"${SUMMARY_JSON}")"
CREATE_P95="$(jq -r '.metrics.primary_order_creation_duration.values["p(95)"] // 0' <<<"${SUMMARY_JSON}")"
FAILED_REQUESTS="$(jq -r '.metrics.cache_failed_requests.values.count // 0' <<<"${SUMMARY_JSON}")"
HTTP_RPS="$(jq -r '.metrics.http_reqs.values.rate' <<<"${SUMMARY_JSON}")"
MAX_VUS="$(jq -r '.metrics.vus_max.values.max' <<<"${SUMMARY_JSON}")"
MAX_CPU="$(sort -nr "${BASELINE_CPU_LOG}" | head -1)"
MAX_FAULT_CPU="$(sort -nr "${FAULT_CPU_LOG}" | head -1)"
MAX_LAG="$(sort -nr "${LAG_LOG}" | head -1)"

test "${MAX_VUS%.*}" -ge 5000
test "${FAILED_REQUESTS%.*}" -eq 0
awk -v value="${HTTP_RPS}" 'BEGIN { exit !(value >= 2000) }'
awk -v value="${MAX_CPU}" 'BEGIN { exit !(value < 15) }'
awk -v value="${MAX_LAG}" 'BEGIN { exit !(value > 0.1) }'
test "${OUTBOX_UNPUBLISHED}" -eq 0
test "${ORDERS_COUNT}" -eq "${READ_MODEL_COUNT}"
test "${ORDERS_PLACED}" -eq "${RESERVATIONS}"
test "${ORDERS_CANCELLED}" -eq "${READ_MODEL_CANCELLED}"
test "${READ_MODEL_COUNT}" -eq "${READ_MODEL_DISTINCT}"

cat <<EOF >"${RESULTS_DIR}/reconciliation.md"
# Phase 17 Post-Test Reconciliation

- Status: PASS
- Orders: ${ORDERS_COUNT}
- Unpublished outbox events: ${OUTBOX_UNPUBLISHED}
- Inventory reservations / placed orders: ${RESERVATIONS} / ${ORDERS_PLACED}
- Order read-model rows / distinct IDs: ${READ_MODEL_COUNT} / ${READ_MODEL_DISTINCT}
- Cancelled source / read-model rows: ${ORDERS_CANCELLED} / ${READ_MODEL_CANCELLED}
EOF

cat <<EOF >"${EVIDENCE_FILE}"
# Phase 17 Evidence Dossier: Cache and Read-Replica Scaling

- **Status:** PASSED
- **Date:** $(date -u +%FT%TZ)
- **Topology:** six-node kind cluster; 3 app pods; 3 Order Query pods; Redis StatefulSet; 3-node Patroni PostgreSQL; 3-broker Kafka
- **Workload:** ${MAX_VUS} peak VUs, ${HTTP_RPS} HTTP requests/second, read-heavy mixed traffic

| Metric | Target | Measured | Result |
|---|---:|---:|---|
| Catalog p95 | < 10 ms | ${CATALOG_P95} ms | PASS |
| Order Query p95 outside injected fallback | < 20 ms | ${QUERY_P95} ms | PASS |
| Order creation p95 | < 200 ms | ${CREATE_P95} ms | PASS |
| Peak concurrency | >= 5,000 VUs | ${MAX_VUS} VUs | PASS |
| Failed requests during Redis deletion and replica replay pause | 0 | ${FAILED_REQUESTS} | PASS |
| Primary PostgreSQL peak CPU outside forced fallback | < 15% of one-core limit | ${MAX_CPU}% | PASS |
| Observed fenced replica lag | > 100 ms | ${MAX_LAG} s | PASS |
| Cross-schema reconciliation | 100% | ${ORDERS_COUNT}/${ORDERS_COUNT} orders | PASS |

## Faults and Recovery

- Redis pod \`redis-0\` was deleted during steady load and the StatefulSet restored it.
- WAL replay was paused on both standbys, application lag gauges exceeded the
  100 ms routing fence, and replay was resumed after ${REPLICA_LAG_SECONDS}s.
- Order Query p95 during the intentionally degraded fault window was
  ${FAULT_QUERY_P95} ms; the normal read-offload SLO is reported separately.
- Primary CPU peaked at ${MAX_FAULT_CPU}% during the intentional lag-fence
  fallback window; this fault-window value is disclosed separately from the
  normal read-offload acceptance measurement.
- No HTTP request failed during the combined qualification.

## Raw Evidence

- \`build/qualification-results/cache/k6-cache-summary.json\`
- \`build/qualification-results/cache/primary-cpu-percent.txt\`
- \`build/qualification-results/cache/primary-cpu-baseline-percent.txt\`
- \`build/qualification-results/cache/primary-cpu-fault-percent.txt\`
- \`build/qualification-results/cache/replica-lag-seconds.txt\`
- \`build/qualification-results/cache/faults.log\`
- \`build/qualification-results/cache/reconciliation.md\`
EOF

echo "=== [CACHE-REPLICA-QUALIFICATION] PASSED with empirical evidence ==="
