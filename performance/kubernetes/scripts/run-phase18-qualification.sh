#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-kind-hyperscale-k8s}"
NAMESPACE="${KUBE_NAMESPACE:-hyperscale}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.57.0}"
TARGET_VUS="${TARGET_VUS:-10000}"
STEADY_DURATION="${STEADY_DURATION:-15m}"
SOAK_VUS="${SOAK_VUS:-1000}"
SOAK_DURATION="${SOAK_DURATION:-30m}"
SPIKE_1X_RATE="${SPIKE_1X_RATE:-500}"
SPIKE_5X_RATE="${SPIKE_5X_RATE:-2500}"
FAULT_VUS="${FAULT_VUS:-1000}"
FAULT_DURATION="${FAULT_DURATION:-3m}"
FAULT_DELAY_SECONDS="${FAULT_DELAY_SECONDS:-90}"
FAULT_HOLD_SECONDS="${FAULT_HOLD_SECONDS:-30}"
JFR_DURATION_SECONDS="${JFR_DURATION_SECONDS:-60}"
ALLOW_DIRTY_WORKTREE="${ALLOW_DIRTY_WORKTREE:-false}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULTS_DIR="${RESULTS_DIR:-${ROOT_DIR}/build/phase18/qualification/${TIMESTAMP}}"
GIT_REVISION="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
EXPECTED_REVISION="${EXPECTED_REVISION:-${GIT_REVISION}}"
CONFIG_NAME="phase18-k6-scripts"
ACTIVE_JOB=""
REPLICA_PODS=""

mkdir -p "${RESULTS_DIR}"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: Required command '$1' is unavailable." >&2
    exit 1
  }
}

duration_seconds() {
  local value="$1" number unit
  if ! [[ "${value}" =~ ^([1-9][0-9]*)([smh])$ ]]; then
    echo "ERROR: Unsupported duration '${value}'; use an integer followed by s, m, or h." >&2
    exit 2
  fi
  number="${BASH_REMATCH[1]}"
  unit="${BASH_REMATCH[2]}"
  case "${unit}" in
    s) echo "${number}" ;;
    m) echo "$((number * 60))" ;;
    h) echo "$((number * 3600))" ;;
  esac
}

kube() {
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" "$@"
}

cleanup() {
  local pod
  for pod in ${REPLICA_PODS}; do
    kube exec "${pod}" -- psql -U hyperscale -d hyperscale \
      -c 'SELECT pg_wal_replay_resume();' >/dev/null 2>&1 || true
  done
  if [ -n "${ACTIVE_JOB}" ]; then
    kube delete job "${ACTIVE_JOB}" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  fi
  kube delete configmap "${CONFIG_NAME}" --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT

for command in kubectl jq awk curl git; do require_command "${command}"; done

if [ "${TARGET_VUS}" -lt 10000 ]; then
  echo "ERROR: TARGET_VUS=${TARGET_VUS}; formal Phase 18 qualification requires at least 10000." >&2
  exit 1
fi
if [ "${SPIKE_5X_RATE}" -ne "$((SPIKE_1X_RATE * 5))" ]; then
  echo "ERROR: SPIKE_5X_RATE must be exactly five times SPIKE_1X_RATE." >&2
  exit 1
fi
if [ "$(duration_seconds "${STEADY_DURATION}")" -lt 900 ]; then
  echo "ERROR: STEADY_DURATION must be at least 15 minutes." >&2
  exit 1
fi
if [ "$(duration_seconds "${SOAK_DURATION}")" -lt 1800 ]; then
  echo "ERROR: SOAK_DURATION must be at least 30 minutes." >&2
  exit 1
fi
if [ "$(duration_seconds "${FAULT_DURATION}")" -lt $((FAULT_DELAY_SECONDS + FAULT_HOLD_SECONDS + 30)) ]; then
  echo "ERROR: FAULT_DURATION must include fault delay, hold, and at least 30 seconds of recovery." >&2
  exit 1
fi
if [ "${JFR_DURATION_SECONDS}" -lt 60 ]; then
  echo "ERROR: JFR_DURATION_SECONDS must be at least 60." >&2
  exit 1
fi

QUALIFYING=true
if ! git -C "${ROOT_DIR}" diff --quiet || ! git -C "${ROOT_DIR}" diff --cached --quiet || \
    [ -n "$(git -C "${ROOT_DIR}" ls-files --others --exclude-standard)" ]; then
  if [ "${ALLOW_DIRTY_WORKTREE}" != "true" ]; then
    echo "ERROR: Formal qualification requires a clean worktree. Commit the reviewed Phase 18 candidate or set ALLOW_DIRTY_WORKTREE=true for a non-qualifying rehearsal." >&2
    exit 1
  fi
  QUALIFYING=false
fi
if [ "${QUALIFYING}" = "true" ] && [ "${EXPECTED_REVISION}" != "${GIT_REVISION}" ]; then
  echo "ERROR: A qualifying run must target the current clean Git revision ${GIT_REVISION}." >&2
  exit 1
fi

kubectl --context "${CONTEXT}" cluster-info >/dev/null
KUBE_CONTEXT="${CONTEXT}" bash "${SCRIPT_DIR}/preflight-cluster.sh"
for workload in deployment/app deployment/order-query deployment/ingress statefulset/redis statefulset/postgres-ha statefulset/kafka; do
  kube rollout status "${workload}" --timeout=300s
done

DEPLOYED_APP_REVISION="$(kube get deployment app -o jsonpath='{.spec.template.metadata.annotations.hyperscale\.com/git-revision}')"
DEPLOYED_QUERY_REVISION="$(kube get deployment order-query -o jsonpath='{.spec.template.metadata.annotations.hyperscale\.com/git-revision}')"
if [ "${DEPLOYED_APP_REVISION}" != "${EXPECTED_REVISION}" ] || \
   [ "${DEPLOYED_QUERY_REVISION}" != "${EXPECTED_REVISION}" ]; then
  echo "ERROR: Deployed revision does not match ${EXPECTED_REVISION}. Deploy with Helm global.revision set to that immutable revision before qualification." >&2
  exit 1
fi

APP_POD="$(kube get pods -l app.kubernetes.io/component=app -o jsonpath='{.items[0].metadata.name}')"
QUERY_POD="$(kube get pods -l app.kubernetes.io/component=order-query -o jsonpath='{.items[0].metadata.name}')"
kube exec "${APP_POD}" -c app -- jcmd 1 VM.version >/dev/null
kube exec "${QUERY_POD}" -c order-query -- jcmd 1 VM.version >/dev/null

cat >"${RESULTS_DIR}/environment.txt" <<EOF
timestamp=${TIMESTAMP}
qualifying=${QUALIFYING}
git_revision=${GIT_REVISION}
expected_revision=${EXPECTED_REVISION}
deployed_app_revision=${DEPLOYED_APP_REVISION}
deployed_query_revision=${DEPLOYED_QUERY_REVISION}
target_vus=${TARGET_VUS}
steady_duration=${STEADY_DURATION}
soak_vus=${SOAK_VUS}
soak_duration=${SOAK_DURATION}
spike_1x_rate=${SPIKE_1X_RATE}
spike_5x_rate=${SPIKE_5X_RATE}
context=${CONTEXT}
namespace=${NAMESPACE}
k6_image=${K6_IMAGE}
EOF
kubectl --context "${CONTEXT}" get nodes -o wide >"${RESULTS_DIR}/nodes.txt"
kube get pods -o wide >"${RESULTS_DIR}/pods-before.txt"
kube get pods -o json >"${RESULTS_DIR}/pods-before.json"
kube get pods -l 'app.kubernetes.io/component in (app,order-query)' -o json \
  | jq -r '.items[] as $pod | $pod.status.containerStatuses[] | [$pod.metadata.name, .name, .image, .imageID] | @tsv' \
  >"${RESULTS_DIR}/application-images.tsv"

kube create configmap "${CONFIG_NAME}" \
  --from-file=qualification-10k.js="${ROOT_DIR}/performance/k6/qualification-10k.js" \
  --from-file=spike-5x.js="${ROOT_DIR}/performance/k6/spike-5x.js" \
  --from-file=config.js="${ROOT_DIR}/performance/k6/lib/config.js" \
  --from-file=endpoints.js="${ROOT_DIR}/performance/k6/lib/endpoints.js" \
  --from-file=journeys.js="${ROOT_DIR}/performance/k6/lib/journeys.js" \
  --from-file=metrics.js="${ROOT_DIR}/performance/k6/lib/metrics.js" \
  --dry-run=client -o yaml | kube apply -f -

discover_database_pods() {
  local pod role
  PRIMARY_POD=""
  REPLICA_PODS=""
  for pod in $(kube get pods -l app.kubernetes.io/component=postgres-ha -o jsonpath='{.items[*].metadata.name}'); do
    role="$(kube exec "${pod}" -- curl -fsS http://localhost:8008/patroni | jq -r '.role // empty')"
    case "${role}" in
      primary|master) PRIMARY_POD="${pod}" ;;
      replica|sync_standby) REPLICA_PODS="${REPLICA_PODS} ${pod}" ;;
    esac
  done
  if [ -z "${PRIMARY_POD}" ] || [ "$(wc -w <<<"${REPLICA_PODS}")" -lt 2 ]; then
    echo "ERROR: Expected one Patroni primary and at least two replicas." >&2
    exit 1
  fi
}

query_primary() {
  kube exec "${PRIMARY_POD}" -- psql -U hyperscale -d hyperscale -t -A -c "$1"
}

kafka_topic_count() {
  local topic="$1"
  kube exec "${KAFKA_POD}" -- kafka-get-offsets \
    --bootstrap-server localhost:9092 --topic "${topic}" \
    | awk -F: '{ total += $3 } END { print total + 0 }'
}

reset_orders() {
  query_primary 'TRUNCATE TABLE "order".order_items, "order".orders, "order".outbox_events, inventory.reservations, order_query.order_read_model CASCADE;' >/dev/null
}

start_job() {
  local scenario="$1" script="$2" vus="$3" duration="$4"
  ACTIVE_JOB="phase18-${scenario}"
  kube delete job "${ACTIVE_JOB}" --ignore-not-found >/dev/null
  cat <<EOF | kube apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: ${ACTIVE_JOB}
spec:
  backoffLimit: 0
  template:
    metadata:
      labels:
        app.kubernetes.io/name: hyperscale-commerce
        app.kubernetes.io/component: phase18-load-generator
        phase18-scenario: ${scenario}
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 12345
        runAsGroup: 12345
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: k6
          image: ${K6_IMAGE}
          args: ["run", "/scripts/${script}"]
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: { drop: ["ALL"] }
          env:
            - { name: APP_BASE_URL, value: "http://hyperscale-ingress.${NAMESPACE}.svc.cluster.local:8080" }
            - { name: ORDER_QUERY_BASE_URL, value: "http://hyperscale-ingress.${NAMESPACE}.svc.cluster.local:8081" }
            - { name: ALLOW_REMOTE_TARGET, value: "true" }
            - { name: QUAL_TARGET_VUS, value: "${vus}" }
            - { name: QUAL_RAMP_UP, value: "60s" }
            - { name: QUAL_STEADY_STATE, value: "${duration}" }
            - { name: QUAL_RAMP_DOWN, value: "60s" }
            - { name: SPIKE_1X_RATE, value: "${SPIKE_1X_RATE}" }
            - { name: SPIKE_5X_RATE, value: "${SPIKE_5X_RATE}" }
            - { name: SPIKE_STAGE1, value: "2m" }
            - { name: SPIKE_RAMP_UP, value: "30s" }
            - { name: SPIKE_BURST, value: "1m" }
            - { name: SPIKE_RAMP_DOWN, value: "30s" }
            - { name: SPIKE_RECOVERY, value: "5m" }
          resources:
            requests: { cpu: "2000m", memory: "2Gi" }
            limits: { cpu: "8000m", memory: "8Gi" }
          volumeMounts:
            - { name: scripts, mountPath: /scripts, readOnly: true }
      volumes:
        - name: scripts
          configMap:
            name: ${CONFIG_NAME}
            items:
              - { key: qualification-10k.js, path: qualification-10k.js }
              - { key: spike-5x.js, path: spike-5x.js }
              - { key: config.js, path: lib/config.js }
              - { key: endpoints.js, path: lib/endpoints.js }
              - { key: journeys.js, path: lib/journeys.js }
              - { key: metrics.js, path: lib/metrics.js }
EOF
  kube wait --for=condition=Ready pod -l job-name="${ACTIVE_JOB}" --timeout=300s
}

sample_resources() {
  local scenario="$1" job="$2" output="${RESULTS_DIR}/${scenario}-resources.txt"
  : >"${output}"
  while :; do
    date -u +%FT%TZ >>"${output}"
    kube top pods >>"${output}" 2>&1 || return 1
    if ! kube get job "${job}" >/dev/null 2>&1; then return 0; fi
    if [ "$(kube get job "${job}" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)" = "1" ] || \
       [ "$(kube get job "${job}" -o jsonpath='{.status.failed}' 2>/dev/null || true)" = "1" ]; then
      return 0
    fi
    sleep 15
  done
}

capture_jfr() {
  local scenario="$1"
  KUBE_CONTEXT="${CONTEXT}" "${ROOT_DIR}/performance/jvm/capture-jvm-diagnostics.sh" \
    --pod "${APP_POD}" --container app --namespace "${NAMESPACE}" \
    --duration "${JFR_DURATION_SECONDS}" --output "${RESULTS_DIR}/${scenario}-app-jvm"
  KUBE_CONTEXT="${CONTEXT}" "${ROOT_DIR}/performance/jvm/capture-jvm-diagnostics.sh" \
    --pod "${QUERY_POD}" --container order-query --namespace "${NAMESPACE}" \
    --duration "${JFR_DURATION_SECONDS}" --output "${RESULTS_DIR}/${scenario}-query-jvm"
}

inject_fault() {
  local fault="$1" pod
  sleep "${FAULT_DELAY_SECONDS}"
  case "${fault}" in
    redis)
      kube delete pod redis-0 --wait=false
      kube wait --for=condition=Ready pod/redis-0 --timeout=180s
      ;;
    replica)
      for pod in ${REPLICA_PODS}; do
        kube exec "${pod}" -- psql -U hyperscale -d hyperscale -c 'SELECT pg_wal_replay_pause();' >/dev/null
      done
      query_primary 'SELECT pg_switch_wal();' >/dev/null
      sleep "${FAULT_HOLD_SECONDS}"
      for pod in ${REPLICA_PODS}; do
        kube exec "${pod}" -- psql -U hyperscale -d hyperscale -c 'SELECT pg_wal_replay_resume();' >/dev/null
      done
      ;;
    kafka)
      pod="$(kube get pods -l app.kubernetes.io/component=kafka -o jsonpath='{.items[0].metadata.name}')"
      kube delete pod "${pod}" --wait=false
      kube rollout status statefulset/kafka --timeout=300s
      ;;
    app)
      pod="$(kube get pods -l app.kubernetes.io/component=app -o jsonpath='{.items[*].metadata.name}' | tr ' ' '\n' | awk -v diagnostic_pod="${APP_POD}" '$0 != diagnostic_pod { print; exit }')"
      if [ -z "${pod}" ]; then
        echo "ERROR: No application pod is available independently of the diagnostic target." >&2
        return 1
      fi
      kube delete pod "${pod}" --wait=false
      kube rollout status deployment/app --timeout=300s
      ;;
    none) ;;
    *) echo "ERROR: Unknown fault '${fault}'." >&2; return 1 ;;
  esac
}

finish_job() {
  local scenario="$1" job="${ACTIVE_JOB}" pod summary
  if ! kube wait --for=condition=complete "job/${job}" --timeout=2400s; then
    pod="$(kube get pods -l job-name="${job}" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    [ -z "${pod}" ] || kube logs "${pod}" >"${RESULTS_DIR}/${scenario}.log" 2>&1 || true
    echo "ERROR: Scenario ${scenario} did not complete successfully." >&2
    exit 1
  fi
  pod="$(kube get pods -l job-name="${job}" -o jsonpath='{.items[0].metadata.name}')"
  kube logs "${pod}" >"${RESULTS_DIR}/${scenario}.log"
  summary="$(sed -n 's/^PHASE18_SUMMARY_JSON=//p' "${RESULTS_DIR}/${scenario}.log" | tail -1)"
  if [ -z "${summary}" ] || ! jq -e . >/dev/null <<<"${summary}"; then
    echo "ERROR: Scenario ${scenario} did not emit valid summary JSON." >&2
    exit 1
  fi
  printf '%s\n' "${summary}" >"${RESULTS_DIR}/${scenario}-summary.json"
  kube delete job "${job}" --wait=true >/dev/null
  ACTIVE_JOB=""
}

run_scenario() {
  local scenario="$1" script="$2" vus="$3" duration="$4" fault="$5"
  echo "=== Phase 18 scenario: ${scenario} ==="
  start_job "${scenario}" "${script}" "${vus}" "${duration}"
  sample_resources "${scenario}" "${ACTIVE_JOB}" &
  RESOURCE_PID=$!
  capture_jfr "${scenario}" &
  JFR_PID=$!
  inject_fault "${fault}" &
  FAULT_PID=$!
  finish_job "${scenario}"
  wait "${RESOURCE_PID}"
  wait "${JFR_PID}"
  wait "${FAULT_PID}"
}

discover_database_pods
KAFKA_POD="$(kube get pods -l app.kubernetes.io/component=kafka -o jsonpath='{.items[0].metadata.name}')"
DLQ_BEFORE="$(kafka_topic_count order-placed-dlq)"
reset_orders

for run in 1 2 3; do
  run_scenario "steady-${run}" qualification-10k.js "${TARGET_VUS}" "${STEADY_DURATION}" none
done
run_scenario spike spike-5x.js "${TARGET_VUS}" "${STEADY_DURATION}" none
run_scenario soak qualification-10k.js "${SOAK_VUS}" "${SOAK_DURATION}" none
for fault in redis replica kafka app; do
  run_scenario "fault-${fault}" qualification-10k.js "${FAULT_VUS}" "${FAULT_DURATION}" "${fault}"
done

echo "Waiting for outbox and projections to drain..."
for _ in $(seq 1 120); do
  ORDERS_COUNT="$(query_primary 'SELECT COUNT(*) FROM "order".orders;')"
  UNPUBLISHED="$(query_primary 'SELECT COUNT(*) FROM "order".outbox_events WHERE published_at IS NULL;')"
  READ_MODEL_COUNT="$(query_primary 'SELECT COUNT(*) FROM order_query.order_read_model;')"
  if [ "${UNPUBLISHED}" -eq 0 ] && [ "${ORDERS_COUNT}" -eq "${READ_MODEL_COUNT}" ]; then break; fi
  sleep 2
done

PLACED="$(query_primary "SELECT COUNT(*) FROM \"order\".orders WHERE status = 'PLACED';")"
CANCELLED="$(query_primary "SELECT COUNT(*) FROM \"order\".orders WHERE status = 'CANCELLED';")"
RESERVATIONS="$(query_primary 'SELECT COUNT(*) FROM inventory.reservations;')"
READ_MODEL_DISTINCT="$(query_primary 'SELECT COUNT(DISTINCT order_id) FROM order_query.order_read_model;')"
READ_MODEL_CANCELLED="$(query_primary "SELECT COUNT(*) FROM order_query.order_read_model WHERE status = 'CANCELLED';")"
DLQ_AFTER="$(kafka_topic_count order-placed-dlq)"
DLQ_DELTA="$((DLQ_AFTER - DLQ_BEFORE))"

cat >"${RESULTS_DIR}/reconciliation.env" <<EOF
orders=${ORDERS_COUNT}
unpublished=${UNPUBLISHED}
placed=${PLACED}
cancelled=${CANCELLED}
reservations=${RESERVATIONS}
read_model=${READ_MODEL_COUNT}
read_model_distinct=${READ_MODEL_DISTINCT}
read_model_cancelled=${READ_MODEL_CANCELLED}
dlq_before=${DLQ_BEFORE}
dlq_after=${DLQ_AFTER}
unexpected_dlq=${DLQ_DELTA}
EOF

test "${UNPUBLISHED}" -eq 0
test "${ORDERS_COUNT}" -eq "${READ_MODEL_COUNT}"
test "${READ_MODEL_COUNT}" -eq "${READ_MODEL_DISTINCT}"
test "${PLACED}" -eq "${RESERVATIONS}"
test "${CANCELLED}" -eq "${READ_MODEL_CANCELLED}"
test "${DLQ_DELTA}" -eq 0

kube get pods -o wide >"${RESULTS_DIR}/pods-after.txt"
"${SCRIPT_DIR}/summarize-phase18-qualification.sh" "${RESULTS_DIR}"

echo "Phase 18 qualification artifacts: ${RESULTS_DIR}"
if [ "${QUALIFYING}" != "true" ]; then
  echo "NON-QUALIFYING rehearsal completed because the worktree was dirty."
  exit 3
fi
