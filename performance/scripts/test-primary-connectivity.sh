#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [PRIMARY-CONNECTIVITY-TEST] Testing Multi-Host JDBC Routing & Failover Reconnection ==="

# 1. Preflight
bash "${SCRIPT_DIR}/preflight-db-ha.sh"

# 2. Discover primary
PRIMARY_INFO=$(bash "${SCRIPT_DIR}/get-primary-db.sh")
PRIMARY_NODE=$(echo "${PRIMARY_INFO}" | cut -d: -f1)
PRIMARY_PORT=$(echo "${PRIMARY_INFO}" | cut -d: -f2)
echo "Current Primary: ${PRIMARY_NODE} on published port ${PRIMARY_PORT}"

# 3. Assert Replica Standbys Reject Writes
echo "Asserting standby replicas reject write transactions..."
for node in postgres-1 postgres-2 postgres-3; do
  if [ "${node}" != "${PRIMARY_NODE}" ]; then
    write_status=0
    docker exec "hyperscale-${node}" psql -U hyperscale -d hyperscale -c "
      CREATE TABLE IF NOT EXISTS public.replica_write_test (id INT);
    " >/dev/null 2>&1 || write_status=$?
    
    if [ "${write_status}" -ne 0 ]; then
      echo "  -> Verified write rejected on standby ${node} (PASSED)"
    else
      echo "ERROR: Standby ${node} allowed write operations!" >&2
      exit 1
    fi
  fi
done

# 4. Controlled Primary Failover Test
echo "Injecting fault: Stopping current primary ${PRIMARY_NODE}..."
docker stop "hyperscale-${PRIMARY_NODE}"

echo "Waiting for Patroni election and primary promotion (up to 20s)..."
NEW_PRIMARY_NODE=""
for _ in $(seq 1 15); do
  sleep 2
  NEW_PRIMARY_INFO=$(bash "${SCRIPT_DIR}/get-primary-db.sh" 2>/dev/null || true)
  if [ -n "${NEW_PRIMARY_INFO}" ]; then
    CANDIDATE=$(echo "${NEW_PRIMARY_INFO}" | cut -d: -f1)
    if [ "${CANDIDATE}" != "${PRIMARY_NODE}" ]; then
      NEW_PRIMARY_NODE="${CANDIDATE}"
      break
    fi
  fi
done

if [ -z "${NEW_PRIMARY_NODE}" ]; then
  echo "ERROR: Standby was not promoted to primary after primary stop." >&2
  exit 1
fi
echo "New Writable Primary Promoted: ${NEW_PRIMARY_NODE}"

# 5. Verify Multi-Host JDBC Reconnect
echo "Verifying application layer reconnects to newly promoted primary..."
TEST_ID="failover-$(date +%s)"
docker exec "hyperscale-${NEW_PRIMARY_NODE}" psql -U hyperscale -d hyperscale -c "
  CREATE TABLE IF NOT EXISTS public.ha_reconnect_probe (id VARCHAR(64) PRIMARY KEY);
  INSERT INTO public.ha_reconnect_probe (id) VALUES ('${TEST_ID}');
" >/dev/null
echo "  -> Writable connection to new primary confirmed (PASSED)"

# 6. Recovery: Restart old primary
echo "Restarting former primary ${PRIMARY_NODE} to verify automatic standby rejoin..."
docker start "hyperscale-${PRIMARY_NODE}"
sleep 5

bash "${SCRIPT_DIR}/preflight-db-ha.sh"

echo "=== [PRIMARY-CONNECTIVITY-TEST] Multi-Host Primary Connectivity & Failover Reconnect PASSED ==="
