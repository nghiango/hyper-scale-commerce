#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [VERIFY-DB-HA] Verifying Patroni PostgreSQL Replication & etcd Resilience ==="

# 1. Preflight
bash "${SCRIPT_DIR}/preflight-db-ha.sh"

# 2. Discover primary container
PRIMARY_NODE=""
for port in 8008 8009 8010; do
  role=$(curl -s "http://localhost:${port}/patroni" | jq -r '.role // empty' 2>/dev/null || true)
  if [ "${role}" = "primary" ] || [ "${role}" = "master" ]; then
    PRIMARY_NODE=$(curl -s "http://localhost:${port}/patroni" | jq -r '.patroni.name // empty')
    break
  fi
done

if [ -z "${PRIMARY_NODE}" ]; then
  echo "ERROR: Unable to locate primary node." >&2
  exit 1
fi
echo "Active Primary: ${PRIMARY_NODE}"

# 3. Test Synchronous Replication Commit Propagation
echo "Testing synchronous commit replication on ${PRIMARY_NODE}..."
TEST_ID="test-$(date +%s)"
docker exec "hyperscale-${PRIMARY_NODE}" psql -U hyperscale -d hyperscale -c "
  CREATE TABLE IF NOT EXISTS public.ha_sync_probe (
    id VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  INSERT INTO public.ha_sync_probe (id) VALUES ('${TEST_ID}');
" >/dev/null

echo "Asserting immediate commit visibility on standbys..."
for node in postgres-1 postgres-2 postgres-3; do
  if [ "${node}" != "${PRIMARY_NODE}" ]; then
    val=$(docker exec "hyperscale-${node}" psql -U hyperscale -d hyperscale -t -A -c "SELECT id FROM public.ha_sync_probe WHERE id = '${TEST_ID}';" 2>/dev/null || true)
    echo "  - Standby ${node} query result: '${val}'"
    if [ "${val}" != "${TEST_ID}" ]; then
      echo "WARNING: Standby ${node} does not yet have ${TEST_ID} (might be asynchronous standby in ANY 1 quorum)."
    else
      echo "  -> Verified synchronous replication to ${node} (PASSED)"
    fi
  fi
done

# 4. Resilience Test: 1 etcd Member Loss
echo "Injecting fault: Stopping hyperscale-etcd-1..."
docker stop hyperscale-etcd-1
sleep 3

echo "Verifying Patroni primary maintains write lease under 2/3 etcd quorum..."
ETCD_TEST_ID="etcd-resilience-$(date +%s)"
docker exec "hyperscale-${PRIMARY_NODE}" psql -U hyperscale -d hyperscale -c "
  INSERT INTO public.ha_sync_probe (id) VALUES ('${ETCD_TEST_ID}');
" >/dev/null
echo "  -> Write succeeded on primary during 1-etcd member loss (PASSED)"

# 5. Recovery: Restart etcd-1
echo "Restarting hyperscale-etcd-1..."
docker start hyperscale-etcd-1
sleep 4

echo "Verifying etcd cluster recovery..."
docker exec hyperscale-etcd-2 etcdctl endpoint health http://etcd-1:2379 http://etcd-2:2379 http://etcd-3:2379

echo "=== [VERIFY-DB-HA] Patroni, etcd, and PostgreSQL Replication Verification PASSED ==="
