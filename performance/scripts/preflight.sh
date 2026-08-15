#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [PREFLIGHT] Checking Load Test Environment ==="

# 1. Check Docker daemon
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon is not running." >&2
  exit 1
fi

# 2. Check ulimit open file descriptors
ULIMIT_N=$(ulimit -n)
echo "INFO: Host open file limit (ulimit -n): ${ULIMIT_N}"
if [ "${ULIMIT_N}" -lt 4096 ]; then
  echo "WARNING: File descriptor limit is low (${ULIMIT_N}). For 10,000 VU qualification, recommend 'ulimit -n 65536'."
fi

# 3. Check service readiness
check_service() {
  local name="$1"
  local url="$2"
  echo "Checking ${name} readiness at ${url}..."
  local attempts=0
  local max_attempts=12
  while [ "${attempts}" -lt "${max_attempts}" ]; do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "  -> ${name} is READY."
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  echo "ERROR: ${name} is NOT ready at ${url} after ${max_attempts} attempts." >&2
  return 1
}

check_service "app" "http://localhost:8080/actuator/health/readiness"
check_service "order-query" "http://localhost:8081/actuator/health/readiness"

# 4. Ensure results directory exists
mkdir -p "${ROOT_DIR}/build/performance-results"

echo "=== [PREFLIGHT] All checks passed successfully ==="
