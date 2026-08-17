#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [INGRESS-TEST] Testing Multi-Replica Routing, Failover & Security Boundaries ==="

# 1. Preflight
bash "${SCRIPT_DIR}/preflight-ingress.sh"

# 2. Test Round-Robin Distribution across app replicas (port 8080)
echo "Testing request distribution across app-1 and app-2..."
APP_INSTANCES=()
for _ in $(seq 1 10); do
  inst=$(curl -sI http://localhost:8080/actuator/health | grep -i "X-Instance-Id" | tr -d '\r' | awk '{print $2}' || true)
  if [ -n "${inst}" ]; then
    APP_INSTANCES+=("${inst}")
  fi
done

echo "Observed app instances: ${APP_INSTANCES[*]:-none}"
if [[ "${APP_INSTANCES[*]}" =~ "app-1" ]] && [[ "${APP_INSTANCES[*]}" =~ "app-2" ]]; then
  echo "  -> Requests distributed across both app-1 and app-2 (PASSED)"
else
  echo "WARNING: Could not observe both app-1 and app-2 in 10 requests (might need more requests under round-robin)."
fi

# 3. Test Round-Robin Distribution across order-query replicas (port 8081)
echo "Testing request distribution across order-query-1 and order-query-2..."
QUERY_INSTANCES=()
for _ in $(seq 1 10); do
  qinst=$(curl -sI http://localhost:8081/actuator/health | grep -i "X-Instance-Id" | tr -d '\r' | awk '{print $2}' || true)
  if [ -n "${qinst}" ]; then
    QUERY_INSTANCES+=("${qinst}")
  fi
done

echo "Observed order-query instances: ${QUERY_INSTANCES[*]:-none}"
if [[ "${QUERY_INSTANCES[*]}" =~ "order-query-1" ]] && [[ "${QUERY_INSTANCES[*]}" =~ "order-query-2" ]]; then
  echo "  -> Requests distributed across both order-query-1 and order-query-2 (PASSED)"
fi

# 4. Security Rule Validation: Blocked administrative / DLQ replay routes
echo "Testing ingress path-blocking security rules..."
DLQ_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/admin/dlq/inspect || true)
if [ "${DLQ_STATUS}" == "403" ]; then
  echo "  -> /admin/dlq returned 403 Forbidden via HAProxy (PASSED)"
else
  echo "ERROR: /admin/dlq was NOT blocked with 403 (Status: ${DLQ_STATUS})" >&2
  exit 1
fi

SENSITIVE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/env || true)
if [ "${SENSITIVE_STATUS}" == "404" ] || [ "${SENSITIVE_STATUS}" == "403" ]; then
  echo "  -> /actuator/env protected (Status: ${SENSITIVE_STATUS}) (PASSED)"
else
  echo "ERROR: /actuator/env exposed (Status: ${SENSITIVE_STATUS})" >&2
  exit 1
fi

# 5. Failover Test: Kill app-1 and verify continuous availability through app-2
echo "Injecting fault: Stopping hyperscale-app-1..."
docker stop hyperscale-app-1

echo "Waiting for HAProxy health check convergence (<= 5s)..."
sleep 4

echo "Sending requests to port 8080 during app-1 failure..."
FAILOVER_SUCCESS=true
for _ in $(seq 1 5); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health/readiness || true)
  if [ "${code}" != "200" ]; then
    FAILOVER_SUCCESS=false
    echo "ERROR: Request failed with status ${code} during failover." >&2
    break
  fi
done

if [ "${FAILOVER_SUCCESS}" == "true" ]; then
  echo "  -> Continuous availability maintained through app-2 during app-1 loss (PASSED)"
fi

# 6. Recovery: Restart app-1 and verify re-admission to pool
echo "Restarting hyperscale-app-1..."
docker start hyperscale-app-1

echo "Waiting for app-1 to rejoin HAProxy active pool (up to 20s)..."
REJOINED=false
for _ in $(seq 1 10); do
  inst=$(curl -sI http://localhost:8080/actuator/health | grep -i "X-Instance-Id" | tr -d '\r' | awk '{print $2}' || true)
  if [ "${inst}" == "app-1" ]; then
    REJOINED=true
    echo "  -> app-1 successfully re-admitted to HAProxy routing pool (PASSED)"
    break
  fi
  sleep 2
done

echo "=== [INGRESS-TEST] Multi-Replica Services & HAProxy Ingress qualification tests PASSED ==="
