#!/usr/bin/env bash
set -euo pipefail

PHASE="${1:-before}" # before or after
OUTPUT_DIR="${2:-build/performance-results/current}"

mkdir -p "${OUTPUT_DIR}"

echo "=== [METRICS SNAPSHOT] Capturing ${PHASE} snapshot into ${OUTPUT_DIR} ==="

# 1. Capture Actuator Prometheus metrics
curl -fsS "http://localhost:8080/actuator/prometheus" > "${OUTPUT_DIR}/app-metrics-${PHASE}.prom" 2>/dev/null || echo "# Failed to scrape app prometheus" > "${OUTPUT_DIR}/app-metrics-${PHASE}.prom"
curl -fsS "http://localhost:8081/actuator/prometheus" > "${OUTPUT_DIR}/order-query-metrics-${PHASE}.prom" 2>/dev/null || echo "# Failed to scrape order-query prometheus" > "${OUTPUT_DIR}/order-query-metrics-${PHASE}.prom"

# 2. Capture Docker stats
docker stats --no-stream --format "{{json .}}" > "${OUTPUT_DIR}/docker-stats-${PHASE}.json" 2>/dev/null || echo "[]" > "${OUTPUT_DIR}/docker-stats-${PHASE}.json"

# 3. Capture Kafka consumer group lag if kafka container exists
if docker ps --format '{{.Names}}' | grep -q 'hyperscale-kafka'; then
  docker exec hyperscale-kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups > "${OUTPUT_DIR}/kafka-lag-${PHASE}.txt" 2>/dev/null || echo "Kafka lag capture unavailable" > "${OUTPUT_DIR}/kafka-lag-${PHASE}.txt"
fi

# 4. Capture PostgreSQL activity
if docker ps --format '{{.Names}}' | grep -q 'hyperscale-postgres'; then
  docker exec hyperscale-postgres psql -U hyperscale -d hyperscale -c "
    SELECT datname, numbackends, xact_commit, xact_rollback, blks_read, blks_hit FROM pg_stat_database WHERE datname='hyperscale';
  " > "${OUTPUT_DIR}/postgres-stat-${PHASE}.txt" 2>/dev/null || true
fi

echo "=== [METRICS SNAPSHOT] ${PHASE} snapshot complete ==="
