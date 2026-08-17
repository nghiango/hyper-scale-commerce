#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [EVENT-QUALIFICATION] Starting Multi-Replica Event Processing & Ordering Qualification ==="

# 1. Ensure services are up and healthy
bash "${SCRIPT_DIR}/preflight-ingress.sh"
bash "${SCRIPT_DIR}/preflight-kafka-ha.sh"

# 2. Reset order data for a clean test run
echo "Resetting order and inventory tables for clean run..."
bash "${SCRIPT_DIR}/reset-order-data.sh"

# 3. Check Initial Consumer Group Partition Assignments
echo "Verifying consumer group partition distribution across replicas..."
CG_DESC=$(docker exec hyperscale-kafka-1 kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group order-query 2>/dev/null || true)
echo "${CG_DESC}"

# 4. Generate Order Traffic across Ingress
echo "Generating orders through HAProxy ingress across app-1 and app-2..."
ORDER_COUNT=20
ORDER_IDS=()
for i in $(seq 1 "${ORDER_COUNT}"); do
  sku_num=$(( (i % 100) + 1 ))
  sku=$(printf "PROD-%06d" "${sku_num}")
  
  response=$(curl -s -X POST http://localhost:8080/orders \
    -H "Content-Type: application/json" \
    -d "{\"items\": [{\"sku\": \"${sku}\", \"quantity\": 1}]}")
  
  order_id=$(echo "${response}" | grep -o '"id":[0-9]*' | cut -d: -f2 || true)
  if [ -n "${order_id}" ]; then
    ORDER_IDS+=("${order_id}")
  fi
  
  # Inject mid-flight failure: stop app-1 at 10 orders
  if [ "${i}" -eq 10 ]; then
    echo "Simulating active outbox worker failure: stopping hyperscale-app-1..."
    docker stop hyperscale-app-1
  fi
  
  # Inject mid-flight consumer failure: stop order-query-1 at 15 orders
  if [ "${i}" -eq 15 ]; then
    echo "Simulating active consumer failure: stopping hyperscale-order-query-1..."
    docker stop hyperscale-order-query-1
  fi
done

echo "Successfully placed ${#ORDER_IDS[@]} orders."

# 5. Verify Surviving Replicas Handled the Traffic
echo "Verifying consumer group rebalance after order-query-1 loss..."
docker exec hyperscale-kafka-1 kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group order-query

# 6. Restart the Stopped Replicas
echo "Restarting stopped replicas (hyperscale-app-1 and hyperscale-order-query-1)..."
docker start hyperscale-app-1
docker start hyperscale-order-query-1

echo "Waiting for replicas to become healthy and consumer groups to rebalance (10s)..."
sleep 10

# 7. Test Duplicate Suppression & Out-of-Order Guard Invariant
echo "Injecting duplicate event into Kafka order-placed topic to verify idempotency..."
if [ "${#ORDER_IDS[@]}" -gt 0 ]; then
  SAMPLE_ORDER_ID="${ORDER_IDS[0]}"
  DUPLICATE_PAYLOAD="{\"version\":1,\"aggregateVersion\":1,\"eventId\":\"$(uuidgen 2>/dev/null || echo 'test-uuid-dup')\",\"orderId\":${SAMPLE_ORDER_ID},\"status\":\"PLACED\",\"items\":[{\"sku\":\"PROD-000001\",\"quantity\":1}],\"createdAt\":\"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\"}"
  echo "${DUPLICATE_PAYLOAD}" | docker exec -i hyperscale-kafka-1 kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic order-placed \
    --producer-property acks=all
  
  echo "Injecting stale version event (version 0) to verify monotonic projection protection..."
  STALE_PAYLOAD="{\"version\":1,\"aggregateVersion\":0,\"eventId\":\"$(uuidgen 2>/dev/null || echo 'test-uuid-stale')\",\"orderId\":${SAMPLE_ORDER_ID},\"status\":\"STALE_STATUS\",\"items\":[{\"sku\":\"PROD-000001\",\"quantity\":1}],\"createdAt\":\"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\"}"
  echo "${STALE_PAYLOAD}" | docker exec -i hyperscale-kafka-1 kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic order-placed \
    --producer-property acks=all
fi

# 8. Bounded Drain Period
echo "Allowing 5s drain for all projections and outbox relay loops..."
sleep 5

# 9. Verify Data Reconciliation & Integrity
echo "Running automated SQL data reconciliation across schemas..."
bash "${SCRIPT_DIR}/reconcile-data.sh"

echo "=== [EVENT-QUALIFICATION] Multi-Replica Event Processing & Ordering Qualification PASSED ==="
