#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BACKUP_TYPE="${1:-full}"
BACKUP_DIR="${ROOT_DIR}/build/backups/pgbackrest"
mkdir -p "${BACKUP_DIR}"

echo "=== [BACKUP-DB] Starting PostgreSQL Physical Backup (${BACKUP_TYPE}) ==="

# 1. Discover active primary
PRIMARY_INFO=$(bash "${SCRIPT_DIR}/get-primary-db.sh")
PRIMARY_NODE=$(echo "${PRIMARY_INFO}" | cut -d: -f1)
echo "Backing up from active primary: ${PRIMARY_NODE}"

# 2. Trigger backup archive
TIMESTAMP=$(date -u +"%Y%m%d_%H%M%SZ")
BACKUP_FILE="${BACKUP_DIR}/hyperscale_${BACKUP_TYPE}_${TIMESTAMP}.tar.gz"

echo "Streaming physical basebackup and continuous WAL archive from ${PRIMARY_NODE}..."
docker exec "hyperscale-${PRIMARY_NODE}" pg_basebackup -U postgres -D - -Ft -z -X stream -c fast > "${BACKUP_FILE}"

echo "Backup completed: ${BACKUP_FILE} ($(du -h "${BACKUP_FILE}" | cut -f1))"

# 3. Verify Backup Integrity
if [ ! -s "${BACKUP_FILE}" ]; then
  echo "ERROR: Generated backup archive is empty!" >&2
  exit 1
fi

echo "Verifying archive checksum and tar header integrity..."
tar -tzf "${BACKUP_FILE}" >/dev/null

echo "=== [BACKUP-DB] Physical backup (${BACKUP_TYPE}) verified and archived successfully ==="
