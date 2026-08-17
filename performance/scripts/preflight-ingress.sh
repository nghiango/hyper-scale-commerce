#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [PREFLIGHT] Checking Multi-Replica Services & HAProxy Ingress ==="

# 1. Check HAProxy container
if ! docker ps --format '{{.Names}}' | grep -q "^hyperscale-haproxy$"; then
  echo "ERROR: hyperscale-haproxy container is not running." >&2
  exit 1
fi
status=$(docker inspect --format '{{.State.Health.Status}}' hyperscale-haproxy 2>/dev/null || echo "unknown")
echo "HAProxy Ingress health: ${status}"
if [ "${status}" != "healthy" ]; then
  echo "ERROR: hyperscale-haproxy is not healthy (status: ${status})." >&2
  exit 1
fi

# 2. Check Backend Replicas
REPLICAS=("hyperscale-app-1" "hyperscale-app-2" "hyperscale-order-query-1" "hyperscale-order-query-2")
for rep in "${REPLICAS[@]}"; do
  if ! docker ps --format '{{.Names}}' | grep -q "^${rep}$"; then
    echo "ERROR: Backend container ${rep} is not running." >&2
    exit 1
  fi
  rep_status=$(docker inspect --format '{{.State.Health.Status}}' "${rep}" 2>/dev/null || echo "unknown")
  echo "Backend ${rep} health: ${rep_status}"
  if [ "${rep_status}" != "healthy" ]; then
    echo "ERROR: Backend ${rep} is not healthy (status: ${rep_status})." >&2
    exit 1
  fi
done

# 3. Test HTTP Ingress reachability
echo "Checking ingress HTTP routing on ports 8080 and 8081..."
if ! curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; then
  echo "ERROR: Cannot reach port 8080 through HAProxy." >&2
  exit 1
fi

if ! curl -fsS http://localhost:8081/actuator/health/readiness >/dev/null 2>&1; then
  echo "ERROR: Cannot reach port 8081 through HAProxy." >&2
  exit 1
fi

echo "=== [PREFLIGHT] Multi-Replica Services & Ingress checks passed successfully ==="
