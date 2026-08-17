#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BOOTSTRAP_SERVERS="${1:-localhost:29092,localhost:29093,localhost:29094}"
PARTITIONS="${PARTITIONS:-3}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-3}"
MIN_INSYNC_REPLICAS="${MIN_INSYNC_REPLICAS:-2}"

echo "=== [KAFKA-HA] Initializing Durable Topics ==="
echo "Bootstrap Servers: ${BOOTSTRAP_SERVERS}"
echo "Partitions: ${PARTITIONS}"
echo "Replication Factor: ${REPLICATION_FACTOR}"
echo "Min In-Sync Replicas: ${MIN_INSYNC_REPLICAS}"

TOPICS=(
  "order-placed"
  "order-placed-dlq"
  "order-cancelled"
  "order-cancelled-dlq"
  "inventory-failed"
  "inventory-failed-dlq"
  "catalog-cache-evict"
  "inventory-cache-evict"
  "order-cache-evict"
  "health-check"
)

# Helper to run kafka-topics inside kafka-1 container or directly via CLI if available
create_topic() {
  local topic="$1"
  echo "Ensuring topic: ${topic}..."
  
  if docker ps --format '{{.Names}}' | grep -q "hyperscale-kafka-1"; then
    docker exec hyperscale-kafka-1 kafka-topics \
      --bootstrap-server localhost:9092 \
      --create \
      --if-not-exists \
      --topic "${topic}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${REPLICATION_FACTOR}" \
      --config min.insync.replicas="${MIN_INSYNC_REPLICAS}" \
      --config unclean.leader.election.enable=false
  elif command -v kafka-topics >/dev/null 2>&1; then
    kafka-topics \
      --bootstrap-server "${BOOTSTRAP_SERVERS}" \
      --create \
      --if-not-exists \
      --topic "${topic}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${REPLICATION_FACTOR}" \
      --config min.insync.replicas="${MIN_INSYNC_REPLICAS}" \
      --config unclean.leader.election.enable=false
  else
    echo "ERROR: Neither docker container hyperscale-kafka-1 nor kafka-topics CLI found." >&2
    exit 1
  fi
}

for topic in "${TOPICS[@]}"; do
  create_topic "${topic}"
done

echo "=== [KAFKA-HA] All durable topics initialized successfully ==="
