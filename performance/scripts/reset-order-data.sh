#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== [RESET DATA] Resetting Order and Inventory transactional tables and Kafka topics for clean load test ==="

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-hyperscale-postgres}"
POSTGRES_USER="${POSTGRES_USER:-hyperscale}"
POSTGRES_DB="${POSTGRES_DB:-hyperscale}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-hyperscale-kafka}"

# 1. Reset Kafka topics
docker exec "${KAFKA_CONTAINER}" /bin/kafka-topics --bootstrap-server localhost:9092 --delete --topic order-placed --if-exists >/dev/null 2>&1 || true
docker exec "${KAFKA_CONTAINER}" /bin/kafka-topics --bootstrap-server localhost:9092 --delete --topic order-placed-dlq --if-exists >/dev/null 2>&1 || true
docker exec "${KAFKA_CONTAINER}" /bin/kafka-topics --bootstrap-server localhost:9092 --delete --topic order-placed-order-query-dlq --if-exists >/dev/null 2>&1 || true
docker exec "${KAFKA_CONTAINER}" /bin/kafka-topics --bootstrap-server localhost:9092 --create --topic order-placed --partitions 3 --replication-factor 1 --if-not-exists >/dev/null 2>&1 || true

# 2. Wait for consumer connection stabilization and truncate database tables
sleep 2
docker exec -i "${POSTGRES_CONTAINER}" psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" <<SQL
TRUNCATE TABLE "order".order_items CASCADE;
TRUNCATE TABLE "order".orders CASCADE;
TRUNCATE TABLE "order".outbox_events CASCADE;
TRUNCATE TABLE inventory.reservations CASCADE;
TRUNCATE TABLE order_query.order_read_model CASCADE;
SQL

echo "=== [RESET DATA] Order and inventory transactional tables and topics are clean ==="
