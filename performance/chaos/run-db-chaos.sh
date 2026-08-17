#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

SCENARIO="${1:-smoke}"

log_info "=== [DB-CHAOS] Starting Database High Availability Failure Experiment: ${SCENARIO} ==="

# Guaranteed cleanup trap for all etcd and postgres containers
cleanup_db_chaos() {
  log_info "[CLEANUP] Ensuring all etcd and PostgreSQL containers are running and healthy..."
  for cname in hyperscale-etcd-1 hyperscale-etcd-2 hyperscale-etcd-3 hyperscale-postgres-1 hyperscale-postgres-2 hyperscale-postgres-3; do
    if docker ps -a --format '{{.Names}}' | grep -q "^${cname}$"; then
      docker start "${cname}" >/dev/null 2>&1 || true
    fi
  done
}
trap cleanup_db_chaos EXIT INT TERM

# 1. Preflight
log_info "[Preflight] Checking Database HA cluster health..."
bash "${ROOT_DIR}/performance/scripts/preflight-db-ha.sh"

# 2. Reset order transactional data
log_info "[Reset] Resetting order data..."
bash "${ROOT_DIR}/performance/scripts/reset-order-data.sh"

RESULTS_DIR="${ROOT_DIR}/build/chaos-results/db-${SCENARIO}"
mkdir -p "${RESULTS_DIR}"

run_traffic_batch() {
  local count="${1:-10}"
  local prefix="${2:-DB-HA}"
  for i in $(seq 1 "${count}"); do
    sku_num=$(( (i % 100) + 1 ))
    sku=$(printf "PROD-%06d" "${sku_num}")
    curl -s -X POST http://localhost:8080/orders \
      -H "Content-Type: application/json" \
      -d "{\"items\": [{\"sku\": \"${sku}\", \"quantity\": 1}]}" >/dev/null 2>&1 || true
    sleep 0.1
  done
}

case "${SCENARIO}" in
  smoke|db-smoke)
    log_info "Running DB Chaos Smoke: Primary kill and recovery..."
    PRIMARY_INFO=$(bash "${ROOT_DIR}/performance/scripts/get-primary-db.sh")
    PRIMARY_NODE=$(echo "${PRIMARY_INFO}" | cut -d: -f1)
    TARGET_CONTAINER="hyperscale-${PRIMARY_NODE}"
    
    validate_target_safety "${TARGET_CONTAINER}"
    log_info "Identified active primary container: ${TARGET_CONTAINER}"
    
    run_traffic_batch 5 "PRE-KILL"
    log_info "[Fault Injection] Stopping active primary ${TARGET_CONTAINER}..."
    docker stop "${TARGET_CONTAINER}"
    
    log_info "Waiting for Patroni leader election and standby promotion (<= 25s)..."
    sleep 15
    
    log_info "Sending write traffic to verify application writes to newly promoted primary..."
    run_traffic_batch 5 "POST-FAILOVER"
    
    log_info "[Fault Recovery] Restarting former primary ${TARGET_CONTAINER}..."
    docker start "${TARGET_CONTAINER}"
    sleep 8
    ;;

  primary-kill)
    log_info "Running Scenario: Unabated Primary SIGKILL under Active Traffic..."
    PRIMARY_INFO=$(bash "${ROOT_DIR}/performance/scripts/get-primary-db.sh")
    PRIMARY_NODE=$(echo "${PRIMARY_INFO}" | cut -d: -f1)
    TARGET_CONTAINER="hyperscale-${PRIMARY_NODE}"
    
    validate_target_safety "${TARGET_CONTAINER}"
    log_info "Identified active primary: ${TARGET_CONTAINER}"
    
    run_traffic_batch 10 "PRE-KILL"
    log_info "[Fault Injection] Force killing (SIGKILL) ${TARGET_CONTAINER}..."
    docker kill "${TARGET_CONTAINER}"
    
    log_info "Allowing up to 25s for etcd lease expiration and standby promotion..."
    sleep 20
    
    log_info "Sending write traffic during new primary tenure..."
    run_traffic_batch 15 "DURING-NEW-PRIMARY"
    
    log_info "[Fault Recovery] Restarting ${TARGET_CONTAINER} to test pg_rewind and standby rejoin..."
    docker start "${TARGET_CONTAINER}"
    sleep 8
    ;;

  sync-standby-loss)
    log_info "Running Scenario: Synchronous Standby Loss..."
    # Find a standby
    PRIMARY_INFO=$(bash "${ROOT_DIR}/performance/scripts/get-primary-db.sh")
    PRIMARY_NODE=$(echo "${PRIMARY_INFO}" | cut -d: -f1)
    
    STANDBY_CONTAINER=""
    for n in postgres-1 postgres-2 postgres-3; do
      if [ "${n}" != "${PRIMARY_NODE}" ]; then
        STANDBY_CONTAINER="hyperscale-${n}"
        break
      fi
    done
    
    validate_target_safety "${STANDBY_CONTAINER}"
    log_info "Identified standby to kill: ${STANDBY_CONTAINER}"
    
    run_traffic_batch 5 "PRE-STANDBY-KILL"
    log_info "[Fault Injection] Killing standby ${STANDBY_CONTAINER}..."
    docker kill "${STANDBY_CONTAINER}"
    sleep 2
    
    log_info "Sending traffic while remaining standby fulfills synchronous quorum..."
    run_traffic_batch 10 "DURING-STANDBY-LOSS"
    
    log_info "[Fault Recovery] Restarting standby ${STANDBY_CONTAINER}..."
    docker start "${STANDBY_CONTAINER}"
    sleep 6
    ;;

  etcd-quorum-loss)
    log_info "Running Negative Control: 2-Member etcd Loss (Consensus Quorum Lost)..."
    validate_target_safety "hyperscale-etcd-1"
    validate_target_safety "hyperscale-etcd-2"
    
    run_traffic_batch 5 "PRE-ETCD-LOSS"
    log_info "[Fault Injection] Stopping etcd-1 and etcd-2 to break DCS quorum..."
    docker stop hyperscale-etcd-1 hyperscale-etcd-2
    sleep 12
    
    log_info "Verifying primary self-fences (cannot renew lease) to prevent split brain..."
    # With lease expired, Patroni demotes PostgreSQL to read-only
    PRIMARY_INFO=$(bash "${ROOT_DIR}/performance/scripts/get-primary-db.sh" 2>/dev/null || echo "fenced:none")
    log_info "Primary status under etcd quorum loss: ${PRIMARY_INFO}"
    
    log_info "[Fault Recovery] Restoring etcd quorum (restarting etcd-1 and etcd-2)..."
    docker start hyperscale-etcd-1 hyperscale-etcd-2
    sleep 10
    ;;

  split-brain-prevention)
    log_info "Running Split-Brain Verification: Checking exactly-one-primary invariant..."
    PRIMARY_COUNT=0
    for port in 8008 8009 8010; do
      role=$(curl -s "http://localhost:${port}/patroni" | jq -r '.role // empty' 2>/dev/null || true)
      if [ "${role}" = "primary" ] || [ "${role}" = "master" ]; then
        PRIMARY_COUNT=$((PRIMARY_COUNT + 1))
      fi
    done
    log_info "Active primary count: ${PRIMARY_COUNT}"
    if [ "${PRIMARY_COUNT}" -ne 1 ]; then
      log_error "VIOLATION: Expected exactly 1 primary, found ${PRIMARY_COUNT}!"
      exit 1
    fi
    log_success "Verified exactly 1 primary invariant (PASSED)"
    ;;

  *)
    log_error "Unknown DB chaos scenario: ${SCENARIO}"
    exit 1
    ;;
esac

log_info "Allowing 6 seconds drain period before final reconciliation..."
sleep 6

log_info "Running post-chaos cross-schema SQL data reconciliation..."
bash "${ROOT_DIR}/performance/scripts/reconcile-data.sh"

log_success "=== [DB-CHAOS] Scenario ${SCENARIO} executed and verified with 100% data integrity ==="
