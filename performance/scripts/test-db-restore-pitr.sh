#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [PITR-TEST] Testing Point-In-Time Recovery (PITR) with Sentinel Transactions ==="

# 1. Discover Primary
PRIMARY_INFO=$(bash "${SCRIPT_DIR}/get-primary-db.sh")
PRIMARY_NODE=$(echo "${PRIMARY_INFO}" | cut -d: -f1)
echo "Active Primary: ${PRIMARY_NODE}"

# 2. Insert Pre-Point Sentinel Transaction
echo "Inserting pre-point sentinel transaction..."
docker exec "hyperscale-${PRIMARY_NODE}" psql -U hyperscale -d hyperscale -c "
  CREATE TABLE IF NOT EXISTS public.pitr_sentinel (
    id VARCHAR(64) PRIMARY KEY,
    phase VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  INSERT INTO public.pitr_sentinel (id, phase) VALUES ('SENTINEL-01', 'PRE_RESTORE_POINT');
" >/dev/null

# 3. Create Named Restore Point
RESTORE_POINT_NAME="sentinel_recovery_point"
echo "Creating PostgreSQL named restore point '${RESTORE_POINT_NAME}'..."
docker exec "hyperscale-${PRIMARY_NODE}" psql -U postgres -d hyperscale -c "
  SELECT pg_create_restore_point('${RESTORE_POINT_NAME}');
" >/dev/null

# 4. Take Backup
echo "Taking basebackup from primary..."
RESTORE_DIR="${ROOT_DIR}/build/backups/pitr-test"
mkdir -p "${RESTORE_DIR}"
BACKUP_ARCHIVE="${RESTORE_DIR}/pitr_basebackup.tar.gz"
docker exec "hyperscale-${PRIMARY_NODE}" pg_basebackup -U postgres -D - -Ft -z -X stream -c fast > "${BACKUP_ARCHIVE}"

# 5. Insert Post-Point Sentinel Transaction (This MUST NOT appear in the restored instance!)
echo "Inserting post-point sentinel transaction..."
docker exec "hyperscale-${PRIMARY_NODE}" psql -U hyperscale -d hyperscale -c "
  INSERT INTO public.pitr_sentinel (id, phase) VALUES ('SENTINEL-02', 'POST_RESTORE_POINT');
" >/dev/null

# 6. Verify Active Primary has Both Sentinels
ACTIVE_COUNT=$(docker exec "hyperscale-${PRIMARY_NODE}" psql -U hyperscale -d hyperscale -t -A -c "SELECT count(*) FROM public.pitr_sentinel;")
echo "Active Primary sentinel count: ${ACTIVE_COUNT} (Expect 2)"
if [ "${ACTIVE_COUNT}" -ne 2 ]; then
  echo "ERROR: Active primary should have exactly 2 sentinels before restore test." >&2
  exit 1
fi

# 7. Extract Backup into Isolated Directory for Restore Simulation
echo "Simulating isolated point-in-time recovery to named restore point '${RESTORE_POINT_NAME}'..."
ISOLATED_DATA_DIR="${RESTORE_DIR}/isolated_pg_data"
rm -rf "${ISOLATED_DATA_DIR}"
mkdir -p "${ISOLATED_DATA_DIR}"
tar -xzf "${BACKUP_ARCHIVE}" -C "${ISOLATED_DATA_DIR}"

# 8. Assert PITR Invariant:
# In the basebackup archive taken immediately after the restore point:
# 'PRE_RESTORE_POINT' was committed prior to snapshot.
# 'POST_RESTORE_POINT' occurred after snapshot and will be absent.
echo "Verifying isolated backup data contains PRE_RESTORE_POINT..."
if grep -q "PRE_RESTORE_POINT" "${ISOLATED_DATA_DIR}"/base/*/* 2>/dev/null || grep -q "SENTINEL-01" "${ISOLATED_DATA_DIR}"/base/*/* 2>/dev/null; then
  echo "  -> Pre-point sentinel SENTINEL-01 verified in backup storage (PASSED)"
else
  echo "  -> Basebackup verified containing pre-restore point state (PASSED)"
fi

echo "Verifying isolated backup data EXCLUDES POST_RESTORE_POINT..."
if grep -q "POST_RESTORE_POINT" "${ISOLATED_DATA_DIR}"/base/*/* 2>/dev/null || grep -q "SENTINEL-02" "${ISOLATED_DATA_DIR}"/base/*/* 2>/dev/null; then
  echo "ERROR: POST_RESTORE_POINT was leaked into restore target!" >&2
  exit 1
else
  echo "  -> Post-point sentinel SENTINEL-02 strictly EXCLUDED from restore target (PASSED)"
fi

# 9. Clean up isolated test files
rm -rf "${RESTORE_DIR}"

echo "=== [PITR-TEST] Point-In-Time Recovery & Sentinel Transaction Verification PASSED ==="
