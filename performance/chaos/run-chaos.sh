#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

SCENARIO="${1:-smoke}"

log_info "=== Starting Chaos Scenario: $SCENARIO ==="

# Trap to guarantee cleanup on exit or failure
trap 'bash "$SCRIPT_DIR/cleanup-chaos.sh"' EXIT INT TERM

# 1. Preflight checks
bash "$SCRIPT_DIR/preflight-chaos.sh"

# 2. Reset database and Kafka partitions
log_info "Resetting data and Kafka topic..."
bash "$ROOT_DIR/performance/scripts/reset-order-data.sh"

# 3. Initialize proxies
bash "$SCRIPT_DIR/init-proxies.sh"

K6_IMAGE="grafana/k6:0.57.0"
RESULTS_DIR="$ROOT_DIR/build/chaos-results/$SCENARIO"
mkdir -p "$RESULTS_DIR"

case "$SCENARIO" in
  smoke)
    log_info "Running Chaos Smoke Scenario..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-smoke.js &
    K6_PID=$!

    sleep 5
    log_info "[Fault Injection] Injecting 250ms latency toxic on Kafka proxy for 6 seconds..."
    bash "$SCRIPT_DIR/inject-toxic.sh" latency kafka 250 50 smoke_kafka_latency

    sleep 6
    log_info "[Fault Recovery] Removing latency toxic..."
    bash "$SCRIPT_DIR/inject-toxic.sh" remove kafka smoke_kafka_latency

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  kafka-control)
    log_info "Running Kafka Control Scenario (No Faults)..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-kafka.js
    ;;

  kafka-latency)
    log_info "Running Kafka Latency + Jitter Scenario (300ms ± 100ms for 15s)..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-kafka.js &
    K6_PID=$!

    sleep 10
    log_info "[Fault Injection] Injecting 300ms ± 100ms latency toxic on Kafka proxy..."
    bash "$SCRIPT_DIR/inject-toxic.sh" latency kafka 300 100 kafka_latency_fault

    sleep 15
    log_info "[Fault Recovery] Removing latency toxic..."
    bash "$SCRIPT_DIR/inject-toxic.sh" remove kafka kafka_latency_fault

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  kafka-slicer)
    log_info "Running Kafka TCP Slicer Scenario for 15s..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-kafka.js &
    K6_PID=$!

    sleep 10
    log_info "[Fault Injection] Injecting TCP Slicer toxic on Kafka proxy..."
    bash "$SCRIPT_DIR/inject-toxic.sh" slicer kafka 128 64 20000 kafka_slicer_fault

    sleep 15
    log_info "[Fault Recovery] Removing slicer toxic..."
    bash "$SCRIPT_DIR/inject-toxic.sh" remove kafka kafka_slicer_fault

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  kafka-cut)
    log_info "Running Kafka Connection Cut Scenario (Broker Unreachable for 20s)..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-kafka.js &
    K6_PID=$!

    sleep 10
    log_info "[Fault Injection] Disabling Kafka proxy (simulating network partition / broker outage)..."
    bash "$SCRIPT_DIR/inject-toxic.sh" down kafka

    sleep 20
    log_info "[Fault Recovery] Re-enabling Kafka proxy..."
    bash "$SCRIPT_DIR/inject-toxic.sh" up kafka

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  kafka-restart)
    log_info "Running Kafka Broker Container Restart Scenario..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-kafka.js &
    K6_PID=$!

    sleep 15
    log_info "[Fault Injection] Restarting hyperscale-kafka container..."
    docker restart hyperscale-kafka

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  postgres-control)
    log_info "Running PostgreSQL Control Scenario (No Faults)..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-postgres.js
    ;;

  postgres-app-latency)
    log_info "Running PostgreSQL App Path Latency Scenario (+200ms ± 50ms for 15s)..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-postgres.js &
    K6_PID=$!

    sleep 10
    log_info "[Fault Injection] Injecting 200ms ± 50ms latency toxic on app_postgres proxy..."
    bash "$SCRIPT_DIR/inject-toxic.sh" latency app_postgres 200 50 postgres_app_latency

    sleep 15
    log_info "[Fault Recovery] Removing latency toxic..."
    bash "$SCRIPT_DIR/inject-toxic.sh" remove app_postgres postgres_app_latency

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  postgres-orderquery-cut)
    log_info "Running PostgreSQL Order-Query Path Outage Scenario for 15s..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-postgres.js &
    K6_PID=$!

    sleep 10
    log_info "[Fault Injection] Disabling orderquery_postgres proxy..."
    bash "$SCRIPT_DIR/inject-toxic.sh" down orderquery_postgres

    sleep 15
    log_info "[Fault Recovery] Re-enabling orderquery_postgres proxy..."
    bash "$SCRIPT_DIR/inject-toxic.sh" up orderquery_postgres

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  postgres-cut)
    log_info "Running Total PostgreSQL Outage Scenario for 15s..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-postgres.js &
    K6_PID=$!

    sleep 10
    log_info "[Fault Injection] Disabling all PostgreSQL proxies..."
    bash "$SCRIPT_DIR/inject-toxic.sh" down app_postgres
    bash "$SCRIPT_DIR/inject-toxic.sh" down orderquery_postgres

    sleep 15
    log_info "[Fault Recovery] Re-enabling all PostgreSQL proxies..."
    bash "$SCRIPT_DIR/inject-toxic.sh" up app_postgres
    bash "$SCRIPT_DIR/inject-toxic.sh" up orderquery_postgres

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  postgres-restart)
    log_info "Running PostgreSQL Container Restart Scenario..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-postgres.js &
    K6_PID=$!

    sleep 15
    log_info "[Fault Injection] Restarting hyperscale-postgres container..."
    docker restart hyperscale-postgres

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  poison-dlq)
    log_info "Running Concurrent Poison-Message and Shared-DLQ Isolation Scenario..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-kafka.js &
    K6_PID=$!

    sleep 15
    log_info "[Fault Injection] Injecting concurrent poison records across all 3 Kafka partitions..."
    bash "$SCRIPT_DIR/inject-poison.sh"

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID

    # Verify DLQ received poison messages
    log_info "Verifying DLQ message isolation..."
    dlq_records=$(docker exec hyperscale-kafka kafka-run-class org.apache.kafka.tools.GetOffsetShell --bootstrap-server localhost:29092 --topic order-placed-dlq --time -1 | awk -F ':' '{sum += $3} END {print sum}')
    log_info "Observed $dlq_records records in 'order-placed-dlq'"
    if [ "${dlq_records:-0}" -lt 3 ]; then
      log_error "Expected at least 3 poison records in DLQ, but found $dlq_records"
      exit 1
    fi
    ;;

  app-crash)
    log_info "Running Application (app) Process Crash & Explicit Restoration Scenario..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-postgres.js &
    K6_PID=$!

    sleep 15
    log_info "[Fault Injection] Abruptly killing hyperscale-app container (SIGKILL)..."
    docker kill hyperscale-app

    sleep 10
    log_info "[Harness Controlled Recovery] Explicitly restarting hyperscale-app container..."
    docker start hyperscale-app

    log_info "Waiting for hyperscale-app healthcheck to be UP..."
    until curl -sf http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; do
      sleep 1
    done
    log_success "hyperscale-app is back UP."

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  order-query-crash)
    log_info "Running Order-Query Process Crash & Explicit Restoration Scenario..."
    docker run --rm \
      --network host \
      -v "$ROOT_DIR/performance/k6:/scripts" \
      -e APP_BASE_URL="http://127.0.0.1:8080" \
      -e ORDER_QUERY_BASE_URL="http://127.0.0.1:8081" \
      "$K6_IMAGE" run /scripts/chaos-postgres.js &
    K6_PID=$!

    sleep 15
    log_info "[Fault Injection] Abruptly killing hyperscale-order-query container (SIGKILL)..."
    docker kill hyperscale-order-query

    sleep 10
    log_info "[Harness Controlled Recovery] Explicitly restarting hyperscale-order-query container..."
    docker start hyperscale-order-query

    log_info "Waiting for hyperscale-order-query healthcheck to be UP..."
    until curl -sf http://127.0.0.1:8081/actuator/health >/dev/null 2>&1; do
      sleep 1
    done
    log_success "hyperscale-order-query is back UP."

    log_info "Waiting for k6 load run to complete..."
    wait $K6_PID
    ;;

  *)
    log_error "Unknown chaos scenario: $SCENARIO"
    exit 1
    ;;
esac

# 4. Outbox drain & Data reconciliation
log_info "Waiting for outbox events to drain..."
drain_start=$(date +%s)
while true; do
  pending=$(docker exec hyperscale-postgres psql -U hyperscale -d hyperscale -t -A -c \
    'SELECT count(*) FROM "order".outbox_events WHERE published_at IS NULL;' 2>/dev/null || echo "0")
  if [ "$pending" -eq 0 ]; then
    break
  fi
  now=$(date +%s)
  if [ $((now - drain_start)) -gt 60 ]; then
    log_error "Outbox drain timed out after 60s ($pending pending events remaining)"
    exit 1
  fi
  log_info "Waiting for $pending pending outbox events..."
  sleep 1
done

# Wait for order_read_model to catch up with orders
log_info "Waiting for order-query projection to catch up with orders..."
poll_start=$(date +%s)
while true; do
  orders_count=$(docker exec hyperscale-postgres psql -U hyperscale -d hyperscale -t -A -c \
    'SELECT count(*) FROM "order".orders;' 2>/dev/null || echo "0")
  query_count=$(docker exec hyperscale-postgres psql -U hyperscale -d hyperscale -t -A -c \
    'SELECT count(*) FROM "order_query".order_read_model;' 2>/dev/null || echo "0")
  if [ "$orders_count" -eq "$query_count" ]; then
    break
  fi
  now=$(date +%s)
  if [ $((now - poll_start)) -gt 30 ]; then
    log_warn "Projection catch-up wait reached 30s ($query_count / $orders_count orders projected)"
    break
  fi
  sleep 1
done

log_info "Running post-chaos data reconciliation..."
bash "$ROOT_DIR/performance/scripts/reconcile-data.sh"

log_success "=== Chaos Scenario $SCENARIO PASSED ==="
