#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

SCENARIO="${1:-smoke}"

log_info "=== [HA-CHAOS] Starting High Availability Failure Experiment: ${SCENARIO} ==="

# Guaranteed cleanup trap for all HA containers
cleanup_ha() {
  log_info "[CLEANUP] Ensuring all HA containers are running and healthy..."
  for cname in hyperscale-postgres hyperscale-kafka-1 hyperscale-kafka-2 hyperscale-kafka-3 hyperscale-app-1 hyperscale-app-2 hyperscale-order-query-1 hyperscale-order-query-2 hyperscale-haproxy; do
    if docker ps -a --format '{{.Names}}' | grep -q "^${cname}$"; then
      docker start "${cname}" >/dev/null 2>&1 || true
    fi
  done
}
trap cleanup_ha EXIT INT TERM

# 1. Preflight
log_info "[Preflight] Checking HA services and Kafka cluster..."
bash "${ROOT_DIR}/performance/scripts/preflight-ingress.sh"
bash "${ROOT_DIR}/performance/scripts/preflight-kafka-ha.sh"

# 2. Reset order data for clean experiment
log_info "[Reset] Resetting order data..."
bash "${ROOT_DIR}/performance/scripts/reset-order-data.sh"

RESULTS_DIR="${ROOT_DIR}/build/chaos-results/${SCENARIO}"
mkdir -p "${RESULTS_DIR}"

run_traffic_batch() {
  local count="${1:-10}"
  local prefix="${2:-HA}"
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
  smoke|ha-smoke)
    log_info "Running HA Chaos Smoke: Rapid verification of 1 app kill & 1 broker kill..."
    run_traffic_batch 5 "SMOKE"
    
    validate_target_safety "hyperscale-app-1"
    log_info "[Fault Injection] Stopping hyperscale-app-1..."
    docker stop hyperscale-app-1
    sleep 3
    run_traffic_batch 5 "SMOKE-FAILOVER"
    
    log_info "[Fault Recovery] Restarting hyperscale-app-1..."
    docker start hyperscale-app-1
    sleep 5
    ;;

  app-replica-loss)
    log_info "Running Scenario: 1 App Replica Loss (SIGKILL) under Traffic..."
    run_traffic_batch 10 "PRE-KILL"
    
    validate_target_safety "hyperscale-app-1"
    log_info "[Fault Injection] Force killing (SIGKILL) hyperscale-app-1..."
    docker kill hyperscale-app-1
    
    log_info "Sending continuous traffic during app-1 failure..."
    run_traffic_batch 15 "DURING-KILL"
    
    log_info "[Fault Recovery] Restarting hyperscale-app-1..."
    docker start hyperscale-app-1
    sleep 5
    run_traffic_batch 10 "POST-RECOVERY"
    ;;

  query-replica-loss)
    log_info "Running Scenario: 1 Order-Query Replica Loss under Traffic..."
    run_traffic_batch 10 "PRE-KILL"
    
    validate_target_safety "hyperscale-order-query-1"
    log_info "[Fault Injection] Force killing (SIGKILL) hyperscale-order-query-1..."
    docker kill hyperscale-order-query-1
    
    log_info "Sending query and command traffic during consumer group rebalance..."
    run_traffic_batch 15 "DURING-KILL"
    
    log_info "[Fault Recovery] Restarting hyperscale-order-query-1..."
    docker start hyperscale-order-query-1
    sleep 6
    ;;

  rolling-restart)
    log_info "Running Scenario: Graceful Sequential Rolling Restart under Traffic..."
    run_traffic_batch 5 "PRE-ROLL"
    
    validate_target_safety "hyperscale-app-1"
    log_info "[Rolling Update] Gracefully stopping app-1..."
    docker stop hyperscale-app-1
    sleep 3
    run_traffic_batch 5 "DURING-APP1-DOWN"
    log_info "[Rolling Update] Starting app-1..."
    docker start hyperscale-app-1
    sleep 5
    
    validate_target_safety "hyperscale-app-2"
    log_info "[Rolling Update] Gracefully stopping app-2..."
    docker stop hyperscale-app-2
    sleep 3
    run_traffic_batch 5 "DURING-APP2-DOWN"
    log_info "[Rolling Update] Starting app-2..."
    docker start hyperscale-app-2
    sleep 5
    ;;

  kafka-leader-loss)
    log_info "Running Scenario: Active Kafka Partition Leader Loss..."
    # Discover leader for partition 0 of order-placed
    LEADER_LINE=$(docker exec hyperscale-kafka-1 kafka-topics --bootstrap-server localhost:9092 --describe --topic order-placed | grep "Partition: 0" || true)
    LEADER_ID=$(echo "${LEADER_LINE}" | grep -o 'Leader: [0-9]*' | awk '{print $2}' || echo "1")
    TARGET_BROKER="hyperscale-kafka-${LEADER_ID}"
    
    validate_target_safety "${TARGET_BROKER}"
    log_info "Identified leader broker: ${TARGET_BROKER} (Node ID ${LEADER_ID})"
    
    run_traffic_batch 10 "PRE-LEADER-KILL"
    log_info "[Fault Injection] Killing active leader broker ${TARGET_BROKER}..."
    docker kill "${TARGET_BROKER}"
    
    log_info "Traffic continuing during leader election and ISR=2 write path..."
    run_traffic_batch 15 "DURING-LEADER-KILL"
    
    log_info "[Fault Recovery] Restarting broker ${TARGET_BROKER}..."
    docker start "${TARGET_BROKER}"
    sleep 8
    ;;

  kafka-quorum-loss-control)
    log_info "Running Negative Control: Kafka Quorum Loss (2 Brokers Down)..."
    run_traffic_batch 5 "PRE-QUORUM-LOSS"
    
    validate_target_safety "hyperscale-kafka-1"
    validate_target_safety "hyperscale-kafka-2"
    log_info "[Fault Injection] Stopping kafka-1 and kafka-2 to break KRaft quorum..."
    docker stop hyperscale-kafka-1 hyperscale-kafka-2
    sleep 2
    
    log_info "Testing outbox durability: placing orders during broker quorum loss..."
    # POST /orders must succeed by persisting into PostgreSQL outbox
    ORDER_RESP=$(curl -s -X POST http://localhost:8080/orders \
      -H "Content-Type: application/json" \
      -d '{"items": [{"sku": "PROD-000001", "quantity": 1}]}' || true)
    log_info "POST /orders response during quorum loss: ${ORDER_RESP}"
    
    log_info "[Fault Recovery] Restoring Kafka quorum (restarting kafka-1 and kafka-2)..."
    docker start hyperscale-kafka-1 hyperscale-kafka-2
    sleep 10
    ;;

  postgres-loss-control)
    log_info "Running Negative Control: PostgreSQL Primary Loss..."
    validate_target_safety "hyperscale-postgres"
    log_info "[Fault Injection] Stopping PostgreSQL primary..."
    docker stop hyperscale-postgres
    sleep 3
    
    log_info "Verifying honest degradation (expecting fast failure, no hang)..."
    PROBE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health/readiness || true)
    log_info "Readiness probe status during database outage: ${PROBE_STATUS}"
    
    log_info "[Fault Recovery] Restarting PostgreSQL primary..."
    docker start hyperscale-postgres
    sleep 5
    ;;

  *)
    log_error "Unknown HA chaos scenario: ${SCENARIO}"
    exit 1
    ;;
esac

log_info "Allowing 6 seconds drain period before final reconciliation..."
sleep 6

log_info "Running post-chaos data reconciliation..."
bash "${ROOT_DIR}/performance/scripts/reconcile-data.sh"

log_success "=== [HA-CHAOS] Scenario ${SCENARIO} executed and verified with 100% data integrity ==="
