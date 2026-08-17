#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BOOTSTRAP_HOST_SERVERS="${1:-localhost:29092,localhost:29093,localhost:29094}"

echo "=== [PREFLIGHT] Checking 3-Broker Kafka HA Cluster ==="

# 1. Check container presence and health
for i in 1 2 3; do
  cname="hyperscale-kafka-${i}"
  if ! docker ps --format '{{.Names}}' | grep -q "^${cname}$"; then
    echo "ERROR: Container ${cname} is not running." >&2
    exit 1
  fi
  status=$(docker inspect --format '{{.State.Health.Status}}' "${cname}" 2>/dev/null || echo "unknown")
  echo "Broker ${i} (${cname}) health: ${status}"
  if [ "${status}" != "healthy" ]; then
    echo "ERROR: Broker ${cname} is not healthy (status: ${status})." >&2
    exit 1
  fi
done

# 2. Check metadata / cluster description
echo "Checking metadata and topic partition replicas..."
TOPIC_DESC=$(docker exec hyperscale-kafka-1 kafka-topics --bootstrap-server localhost:9092 --describe)

echo "${TOPIC_DESC}"

# 3. Assert no offline partitions
OFFLINE_PARTITIONS=$(docker exec hyperscale-kafka-1 kafka-topics --bootstrap-server localhost:9092 --describe --unavailable-partitions)
if [ -n "${OFFLINE_PARTITIONS}" ]; then
  echo "ERROR: Found offline/unavailable partitions:" >&2
  echo "${OFFLINE_PARTITIONS}" >&2
  exit 1
fi
echo "  -> Offline partitions: 0 (PASSED)"

# 4. Check under-replicated partitions
UNDER_REPLICATED=$(docker exec hyperscale-kafka-1 kafka-topics --bootstrap-server localhost:9092 --describe --under-replicated-partitions)
if [ -n "${UNDER_REPLICATED}" ]; then
  echo "WARNING: Found under-replicated partitions (may be transient during rebalance):"
  echo "${UNDER_REPLICATED}"
else
  echo "  -> Under-replicated partitions: 0 (PASSED)"
fi

# 5. Check required topics and min.insync.replicas
REQUIRED_TOPICS=(
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

for topic in "${REQUIRED_TOPICS[@]}"; do
  if ! echo "${TOPIC_DESC}" | grep -q "Topic: ${topic}"; then
    echo "ERROR: Required topic '${topic}' is missing." >&2
    exit 1
  fi
  
  # Check config min.insync.replicas
  CONFIGS=$(docker exec hyperscale-kafka-1 kafka-configs --bootstrap-server localhost:9092 --entity-type topics --entity-name "${topic}" --describe)
  if ! echo "${CONFIGS}" | grep -q "min.insync.replicas=2"; then
    echo "ERROR: Topic '${topic}' does not have min.insync.replicas=2 configured. Configs: ${CONFIGS}" >&2
    exit 1
  fi
done
echo "  -> Required topics exist with min.insync.replicas=2 (PASSED)"

echo "=== [PREFLIGHT] Kafka 3-Node HA Cluster verification succeeded ==="
