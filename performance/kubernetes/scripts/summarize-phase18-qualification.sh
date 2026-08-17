#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -d "$1" ]; then
  echo "Usage: $0 RESULTS_DIRECTORY" >&2
  exit 2
fi

RESULTS_DIR="$(cd "$1" && pwd)"
REPORT="${RESULTS_DIR}/qualification-report.md"

for scenario in steady-1 steady-2 steady-3 spike soak fault-redis fault-replica fault-kafka fault-app; do
  test -s "${RESULTS_DIR}/${scenario}-summary.json" || {
    echo "ERROR: Missing summary for ${scenario}." >&2
    exit 1
  }
  jq -e . "${RESULTS_DIR}/${scenario}-summary.json" >/dev/null
done
test -s "${RESULTS_DIR}/reconciliation.env"
test -s "${RESULTS_DIR}/environment.txt"
if ! grep -qx 'qualifying=true' "${RESULTS_DIR}/environment.txt"; then
  echo "ERROR: Artifact set is explicitly non-qualifying." >&2
  exit 1
fi

for scenario in steady-1 steady-2 steady-3 spike soak fault-redis fault-replica fault-kafka fault-app; do
  test -s "${RESULTS_DIR}/${scenario}-resources.txt" || {
    echo "ERROR: Missing resource samples for ${scenario}." >&2
    exit 1
  }
  for service in app query; do
    for artifact in recording.jfr summary.md thread-dump.txt heap-info.txt class-histogram.txt native-memory.txt jfr-summary.txt; do
      test -s "${RESULTS_DIR}/${scenario}-${service}-jvm/${artifact}" || {
        echo "ERROR: Missing ${scenario}/${service} JVM artifact ${artifact}." >&2
        exit 1
      }
    done
    grep -q '^- Java-level deadlocks: 0$' "${RESULTS_DIR}/${scenario}-${service}-jvm/summary.md" || {
      echo "ERROR: ${scenario}/${service} captured a Java-level deadlock." >&2
      exit 1
    }
    grep -q '^- Virtual-thread pinned events: 0$' "${RESULTS_DIR}/${scenario}-${service}-jvm/summary.md" || {
      echo "ERROR: ${scenario}/${service} has pinned events or the measurement is unavailable." >&2
      exit 1
    }
  done
done

metric() {
  local file="$1" name="$2" field="$3"
  jq -er --arg name "${name}" --arg field "${field}" '.metrics[$name].values[$field]' "${file}"
}

assert_less_than() {
  local value="$1" limit="$2" label="$3"
  awk -v value="${value}" -v limit="${limit}" 'BEGIN { exit !(value < limit) }' || {
    echo "ERROR: ${label}=${value}, required < ${limit}." >&2
    exit 1
  }
}

assert_at_least() {
  local value="$1" limit="$2" label="$3"
  awk -v value="${value}" -v limit="${limit}" 'BEGIN { exit !(value >= limit) }' || {
    echo "ERROR: ${label}=${value}, required >= ${limit}." >&2
    exit 1
  }
}

steady_rows=""
worst_critical=0
worst_catalog=0
worst_query=0
worst_failure=0
min_rps=""
for run in 1 2 3; do
  file="${RESULTS_DIR}/steady-${run}-summary.json"
  vus="$(metric "${file}" vus_max max)"
  critical="$(metric "${file}" critical_api_duration 'p(95)')"
  catalog_detail="$(metric "${file}" catalog_get_product_by_id_duration 'p(95)')"
  catalog_list="$(metric "${file}" catalog_list_products_duration 'p(95)')"
  query_detail="$(metric "${file}" order_query_get_order_by_id_duration 'p(95)')"
  query_list="$(metric "${file}" order_query_list_orders_duration 'p(95)')"
  failures="$(metric "${file}" http_req_failed rate)"
  dropped="$(jq -r '.metrics.dropped_iterations.values.count // 0' "${file}")"
  rps="$(metric "${file}" http_reqs rate)"
  catalog="$(printf '%s\n%s\n' "${catalog_detail}" "${catalog_list}" | sort -nr | head -1)"
  query="$(printf '%s\n%s\n' "${query_detail}" "${query_list}" | sort -nr | head -1)"
  assert_at_least "${vus}" 10000 "steady-${run} max VUs"
  assert_less_than "${critical}" 200 "steady-${run} critical p95"
  assert_less_than "${catalog}" 10 "steady-${run} catalog p95"
  assert_less_than "${query}" 20 "steady-${run} Order Query p95"
  assert_less_than "${failures}" 0.001 "steady-${run} failure rate"
  assert_less_than "${dropped}" 1 "steady-${run} dropped iterations"
  worst_critical="$(printf '%s\n%s\n' "${worst_critical}" "${critical}" | sort -nr | head -1)"
  worst_catalog="$(printf '%s\n%s\n' "${worst_catalog}" "${catalog}" | sort -nr | head -1)"
  worst_query="$(printf '%s\n%s\n' "${worst_query}" "${query}" | sort -nr | head -1)"
  worst_failure="$(printf '%s\n%s\n' "${worst_failure}" "${failures}" | sort -nr | head -1)"
  if [ -z "${min_rps}" ]; then min_rps="${rps}"; else min_rps="$(printf '%s\n%s\n' "${min_rps}" "${rps}" | sort -n | head -1)"; fi
  steady_rows="${steady_rows}| ${run} | ${vus} | ${rps} | ${critical} ms | ${catalog} ms | ${query} ms | ${failures} |\n"
done

spike_file="${RESULTS_DIR}/spike-summary.json"
spike_critical="$(metric "${spike_file}" critical_api_duration 'p(95)')"
spike_failures="$(metric "${spike_file}" http_req_failed rate)"
spike_dropped="$(jq -r '.metrics.dropped_iterations.values.count // 0' "${spike_file}")"
baseline_critical="$(metric "${spike_file}" 'critical_api_duration{scenario:spike_baseline}' 'p(95)')"
recovery_critical="$(metric "${spike_file}" 'critical_api_duration{scenario:spike_recovery_gate}' 'p(95)')"
baseline_failures="$(metric "${spike_file}" 'http_req_failed{scenario:spike_baseline}' rate)"
recovery_failures="$(metric "${spike_file}" 'http_req_failed{scenario:spike_recovery_gate}' rate)"
recovery_limit="$(awk -v baseline="${baseline_critical}" 'BEGIN { print baseline * 1.10 }')"
assert_less_than "${spike_critical}" 200 "spike critical p95"
assert_less_than "${spike_failures}" 0.001 "spike failure rate"
assert_less_than "${spike_dropped}" 1 "spike dropped iterations"
assert_less_than "${baseline_failures}" 0.001 "pre-spike failure rate"
assert_less_than "${recovery_failures}" 0.001 "final recovery-window failure rate"
assert_less_than "${recovery_critical}" "${recovery_limit}" "final recovery-window p95 versus pre-spike band"

soak_file="${RESULTS_DIR}/soak-summary.json"
soak_critical="$(metric "${soak_file}" critical_api_duration 'p(95)')"
soak_failures="$(metric "${soak_file}" http_req_failed rate)"
soak_dropped="$(jq -r '.metrics.dropped_iterations.values.count // 0' "${soak_file}")"
assert_less_than "${soak_critical}" 200 "soak critical p95"
assert_less_than "${soak_failures}" 0.001 "soak failure rate"
assert_less_than "${soak_dropped}" 1 "soak dropped iterations"

for fault in redis replica kafka app; do
  file="${RESULTS_DIR}/fault-${fault}-summary.json"
  failures="$(metric "${file}" http_req_failed rate)"
  critical="$(metric "${file}" critical_api_duration 'p(95)')"
  dropped="$(jq -r '.metrics.dropped_iterations.values.count // 0' "${file}")"
  assert_less_than "${failures}" 0.001 "fault-${fault} failure rate"
  assert_less_than "${critical}" 200 "fault-${fault} critical p95"
  assert_less_than "${dropped}" 1 "fault-${fault} dropped iterations"
done

set -a
# shellcheck disable=SC1090
source "${RESULTS_DIR}/reconciliation.env"
set +a
test "${unpublished}" -eq 0
test "${orders}" -eq "${read_model}"
test "${read_model}" -eq "${read_model_distinct}"
test "${placed}" -eq "${reservations}"
test "${cancelled}" -eq "${read_model_cancelled}"
test "${unexpected_dlq}" -eq 0

cat >"${REPORT}" <<EOF
# Phase 18 Kubernetes/JVM Qualification Artifact Report

This report is generated exclusively from captured k6 summaries and SQL
reconciliation artifacts. Committed phase evidence must additionally record
the clean revision and review the JFR/resource bundles in this directory.

## Three consecutive 10,000-VU runs

| Run | Max VUs | RPS | Critical p95 | Catalog worst p95 | Order Query worst p95 | Failure rate |
|---:|---:|---:|---:|---:|---:|---:|
$(printf '%b' "${steady_rows}")

- Worst critical p95: ${worst_critical} ms
- Worst catalog p95: ${worst_catalog} ms
- Worst Order Query p95: ${worst_query} ms
- Worst failure rate: ${worst_failure}
- Minimum run throughput: ${min_rps} RPS

## Spike

- Critical p95: ${spike_critical} ms
- Failure rate: ${spike_failures}
- Dropped iterations: ${spike_dropped}
- Pre-spike critical p95: ${baseline_critical} ms
- Final recovery-window critical p95: ${recovery_critical} ms (required below ${recovery_limit} ms)
- Final recovery-window failure rate: ${recovery_failures}

## Soak

- Critical p95: ${soak_critical} ms
- Failure rate: ${soak_failures}
- Dropped iterations: ${soak_dropped}

## Reconciliation

- Orders/read model: ${orders}/${read_model}
- Unpublished outbox events: ${unpublished}
- Placed/reservations: ${placed}/${reservations}
- Cancelled/read-model cancelled: ${cancelled}/${read_model_cancelled}
- Unexpected DLQ messages added: ${unexpected_dlq}
EOF

echo "Phase 18 artifact gates PASSED: ${REPORT}"
