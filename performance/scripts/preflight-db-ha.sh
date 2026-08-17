#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [PREFLIGHT] Checking PostgreSQL / Patroni 3-Node HA & etcd Cluster ==="

# 1. Check etcd 3-member quorum
echo "Checking etcd cluster containers..."
for e in hyperscale-etcd-1 hyperscale-etcd-2 hyperscale-etcd-3; do
  if ! docker ps --format '{{.Names}}' | grep -q "^${e}$"; then
    echo "ERROR: etcd container ${e} is not running." >&2
    exit 1
  fi
  status=$(docker inspect --format '{{.State.Health.Status}}' "${e}" 2>/dev/null || echo "unknown")
  echo "  - ${e} health: ${status}"
  if [ "${status}" != "healthy" ]; then
    echo "ERROR: ${e} is not healthy (status: ${status})." >&2
    exit 1
  fi
done

# 2. Check Patroni / PostgreSQL nodes
echo "Checking Patroni / PostgreSQL nodes..."
for p in hyperscale-postgres-1 hyperscale-postgres-2 hyperscale-postgres-3; do
  if ! docker ps --format '{{.Names}}' | grep -q "^${p}$"; then
    echo "ERROR: PostgreSQL container ${p} is not running." >&2
    exit 1
  fi
  p_status=$(docker inspect --format '{{.State.Health.Status}}' "${p}" 2>/dev/null || echo "unknown")
  echo "  - ${p} health: ${p_status}"
  if [ "${p_status}" != "healthy" ]; then
    echo "ERROR: ${p} is not healthy (status: ${p_status})." >&2
    exit 1
  fi
done

# 3. Discover Primary and Replicas via Patroni REST API
echo "Discovering Patroni cluster topology..."
PRIMARY_NODE=""
STANDBY_NODES=()

for port in 8008 8009 8010; do
  patroni_resp=$(curl -s "http://localhost:${port}/patroni" || true)
  role=$(echo "${patroni_resp}" | jq -r '.role // empty' 2>/dev/null || true)
  node_name=$(echo "${patroni_resp}" | jq -r '.patroni.name // empty' 2>/dev/null || true)
  
  if [ "${role}" = "primary" ] || [ "${role}" = "master" ]; then
    PRIMARY_NODE="${node_name}"
    echo "  -> Found Writable Primary: ${node_name} (Port ${port})"
  elif [ "${role}" = "replica" ] || [ "${role}" = "sync_standby" ]; then
    STANDBY_NODES+=("${node_name}")
    echo "  -> Found Standby Replica: ${node_name} (${role}, Port ${port})"
  fi
done

if [ -z "${PRIMARY_NODE}" ]; then
  echo "ERROR: No writable primary found in Patroni cluster!" >&2
  exit 1
fi

if [ "${#STANDBY_NODES[@]}" -lt 2 ]; then
  echo "ERROR: Expected at least 2 standbys, found ${#STANDBY_NODES[@]}." >&2
  exit 1
fi

# 4. Check Synchronous Replication Setting
echo "Checking synchronous replication status on primary (${PRIMARY_NODE})..."
SYNC_SETTING=$(docker exec "hyperscale-${PRIMARY_NODE}" psql -U hyperscale -d hyperscale -t -A -c "SHOW synchronous_standby_names;" 2>/dev/null || true)
echo "  - synchronous_standby_names: '${SYNC_SETTING}'"

SYNC_STATE=$(docker exec "hyperscale-${PRIMARY_NODE}" psql -U hyperscale -d hyperscale -t -A -c "SELECT sync_state FROM pg_stat_replication LIMIT 1;" 2>/dev/null || true)
echo "  - pg_stat_replication sync_state: '${SYNC_STATE}'"

echo "=== [PREFLIGHT] PostgreSQL / Patroni HA & etcd cluster preflight passed ==="
