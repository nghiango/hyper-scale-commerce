#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="hyperscale-redis-verify-test"
VOLUME_NAME="hyperscale-redis-verify-vol"
PASSWORD="redis_verify_secret_password"

echo "=== [REDIS-RUNTIME-VERIFY] Testing Redis 7.2 Container Runtime, Auth & Persistence ==="

cleanup() {
  echo "Cleaning up temporary verification resources..."
  docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  docker volume rm "${VOLUME_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 1. Create persistent volume
docker volume create "${VOLUME_NAME}" >/dev/null

# 2. Start Redis container with matching Kubernetes specs (non-root 999, password, appendonly)
echo "[Step 1] Starting Redis 7.2 container with non-root security context and auth..."
docker run -d \
  --name "${CONTAINER_NAME}" \
  --user 999:999 \
  -v "${VOLUME_NAME}:/data" \
  redis:7.2-alpine \
  redis-server --requirepass "${PASSWORD}" --appendonly yes >/dev/null

sleep 2

# 3. Verify Non-Root User UID
echo "[Step 2] Verifying running process user is non-root (UID 999)..."
UID_IN_CONTAINER=$(docker exec "${CONTAINER_NAME}" id -u)
echo "  -> Redis process running as UID: ${UID_IN_CONTAINER}"
if [ "${UID_IN_CONTAINER}" != "999" ]; then
  echo "ERROR: Expected UID 999, but got ${UID_IN_CONTAINER}" >&2
  exit 1
fi

# 4. Verify Authentication Enforcement
echo "[Step 3] Verifying authentication enforcement..."
if docker exec "${CONTAINER_NAME}" redis-cli ping 2>&1 | grep -q "NOAUTH"; then
  echo "  -> Verified unauthenticated requests are rejected with NOAUTH (PASSED)"
else
  echo "ERROR: Unauthenticated request was not rejected!" >&2
  exit 1
fi

AUTH_PING=$(docker exec "${CONTAINER_NAME}" redis-cli -a "${PASSWORD}" ping 2>/dev/null)
if [ "${AUTH_PING}" = "PONG" ]; then
  echo "  -> Verified authenticated PING returns PONG (PASSED)"
else
  echo "ERROR: Authenticated PING failed: ${AUTH_PING}" >&2
  exit 1
fi

# 5. Write Data and Verify Key Storage
echo "[Step 4] Writing key-value pairs with TTL..."
docker exec "${CONTAINER_NAME}" redis-cli -a "${PASSWORD}" set test_key "hyperscale_cache_payload" ex 300 >/dev/null 2>&1
STORED_VAL=$(docker exec "${CONTAINER_NAME}" redis-cli -a "${PASSWORD}" get test_key 2>/dev/null)
if [ "${STORED_VAL}" = "hyperscale_cache_payload" ]; then
  echo "  -> Stored and retrieved key successfully (PASSED)"
else
  echo "ERROR: Stored value mismatch: ${STORED_VAL}" >&2
  exit 1
fi

# 6. Test Persistence Across Container Restart
echo "[Step 5] Restarting container to verify volume persistence (Append-Only Log)..."
docker restart "${CONTAINER_NAME}" >/dev/null
sleep 2

RECOVERED_VAL=$(docker exec "${CONTAINER_NAME}" redis-cli -a "${PASSWORD}" get test_key 2>/dev/null)
if [ "${RECOVERED_VAL}" = "hyperscale_cache_payload" ]; then
  echo "  -> Verified key persisted across container restart from AOF storage (PASSED)"
else
  echo "ERROR: Key lost after restart: ${RECOVERED_VAL}" >&2
  exit 1
fi

echo "=== [REDIS-RUNTIME-VERIFY] All Redis Runtime & Persistence Tests PASSED ==="
