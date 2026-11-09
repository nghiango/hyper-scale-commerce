#!/usr/bin/env bash
# Simulate the buyer flow end-to-end and print the metrics that matter.
#
# Usage:
#   ./postman/simulate-flow.sh              # one order
#   ./postman/simulate-flow.sh 10           # ten orders
#
# Requires the local stack: `make services` and seeded catalog
# (performance/scripts/seed-data.sh creates PERF-SKU-00001..01000).
set -euo pipefail

APP="${APP_BASE_URL:-http://localhost:8080}"
OQ="${ORDER_QUERY_BASE_URL:-http://localhost:8081}"
COUNT="${1:-1}"

step() { printf '\n=== %s ===\n' "$*"; }

step "1. Health"
curl -fsS "$APP/actuator/health" | head -c 400; echo
curl -fsS "$OQ/actuator/health" | head -c 400; echo

step "2. Browse catalog (app :8080)"
curl -fsS "$APP/catalog/products?page=0&size=5" | head -c 800; echo

for i in $(seq 1 "$COUNT"); do
  step "3.$i Create order (Idempotency-Key set)"
  SKU="PERF-SKU-$(printf '%05d' $(( (RANDOM % 1000) + 1 )))"
  ORDER_JSON=$(curl -fsS -X POST "$APP/orders" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: flow-$(date +%s)-$i" \
    -d "{\"items\":[{\"sku\":\"$SKU\",\"quantity\":1}]}")
  echo "$ORDER_JSON"
  ORDER_ID=$(echo "$ORDER_JSON" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)

  step "3.$i Poll read model for order $ORDER_ID (async projection)"
  for attempt in $(seq 1 20); do
    if curl -s -o /dev/null -w '%{http_code}' "$OQ/orders/$ORDER_ID" | grep -q '^200$'; then
      echo "Order $ORDER_ID projected after ~$((attempt * 500))ms"
      break
    fi
    [ "$attempt" -eq 20 ] && echo "WARN: order $ORDER_ID not projected within 10s"
    sleep 0.5
  done
done

step "4. List orders (read model :8081)"
curl -fsS "$OQ/orders?page=0&size=5" | head -c 800; echo

step "5. Metrics — app (POST /orders latency)"
curl -fsS "$APP/actuator/prometheus" | grep 'http_server_requests_seconds' | grep 'uri="/orders"' | head -8

step "6. Metrics — order-query (GET /orders + kafka consumer)"
curl -fsS "$OQ/actuator/prometheus" | grep -E 'http_server_requests_seconds_(count|sum)|kafka_consumer_fetch' | grep -v '^#' | grep 'orders\|consumer' | head -12

step "Done"
