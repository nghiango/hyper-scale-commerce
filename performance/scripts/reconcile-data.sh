#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${1:-build/performance-results/current}"
mkdir -p "${OUTPUT_DIR}"

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-hyperscale-postgres}"
POSTGRES_USER="${POSTGRES_USER:-hyperscale}"
POSTGRES_DB="${POSTGRES_DB:-hyperscale}"

echo "=== [DATA RECONCILIATION] Verifying Cross-Schema Business Integrity ==="

query_pg() {
  local sql="$1"
  docker exec -i "${POSTGRES_CONTAINER}" psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -t -A -c "${sql}"
}

ORDERS_COUNT=$(query_pg 'SELECT COUNT(*) FROM "order".orders;')
OUTBOX_TOTAL=$(query_pg 'SELECT COUNT(*) FROM "order".outbox_events;')
OUTBOX_UNPUBLISHED=$(query_pg 'SELECT COUNT(*) FROM "order".outbox_events WHERE published_at IS NULL;')
INVENTORY_RESERVATIONS=$(query_pg 'SELECT COUNT(*) FROM inventory.reservations;')
READ_MODEL_COUNT=$(query_pg 'SELECT COUNT(*) FROM order_query.order_read_model;')
READ_MODEL_DISTINCT=$(query_pg 'SELECT COUNT(DISTINCT order_id) FROM order_query.order_read_model;')

# DLQ Count
DLQ_COUNT=0
if docker ps --format '{{.Names}}' | grep -q 'hyperscale-kafka'; then
  # Check if there are messages in order-placed-dlq topic (offsets)
  DLQ_OFFSET=$(docker exec hyperscale-kafka kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic order-placed-dlq --time -1 2>/dev/null | awk -F ':' '{sum += $3} END {print sum}' || echo "0")
  if [ -n "${DLQ_OFFSET}" ]; then
    DLQ_COUNT="${DLQ_OFFSET}"
  fi
fi

STATUS="PASS"
FAILURES=""

if [ "${ORDERS_COUNT}" -ne "${OUTBOX_TOTAL}" ]; then
  STATUS="FAIL"
  FAILURES="${FAILURES}\n- Mismatch: Orders count (${ORDERS_COUNT}) != Outbox events total (${OUTBOX_TOTAL})"
fi

if [ "${OUTBOX_UNPUBLISHED}" -ne 0 ]; then
  STATUS="FAIL"
  FAILURES="${FAILURES}\n- Unpublished outbox events remaining: ${OUTBOX_UNPUBLISHED}"
fi

if [ "${ORDERS_COUNT}" -ne "${INVENTORY_RESERVATIONS}" ]; then
  STATUS="FAIL"
  FAILURES="${FAILURES}\n- Mismatch: Orders count (${ORDERS_COUNT}) != Inventory reservations (${INVENTORY_RESERVATIONS})"
fi

if [ "${ORDERS_COUNT}" -ne "${READ_MODEL_COUNT}" ]; then
  STATUS="FAIL"
  FAILURES="${FAILURES}\n- Mismatch: Orders count (${ORDERS_COUNT}) != Order Query Read Model rows (${READ_MODEL_COUNT})"
fi

if [ "${READ_MODEL_COUNT}" -ne "${READ_MODEL_DISTINCT}" ]; then
  STATUS="FAIL"
  FAILURES="${FAILURES}\n- Duplicate Order IDs detected in Read Model: total=${READ_MODEL_COUNT}, distinct=${READ_MODEL_DISTINCT}"
fi

if [ "${DLQ_COUNT}" -ne 0 ]; then
  STATUS="FAIL"
  FAILURES="${FAILURES}\n- Dead Letter Queue contains messages: ${DLQ_COUNT}"
fi

cat <<EOF > "${OUTPUT_DIR}/reconciliation.md"
# Post-Test Data Reconciliation Report

- **Status:** ${STATUS}
- **Timestamp (UTC):** $(date -u +"%Y-%m-%dT%H:%M:%SZ")

| Schema / Metric | Queried Entity | Count | Expected | Match |
|---|---|---|---|---|
| \`order\` | \`order.orders\` | ${ORDERS_COUNT} | Base | YES |
| \`order\` | \`order.outbox_events\` | ${OUTBOX_TOTAL} | == ${ORDERS_COUNT} | $([ "${ORDERS_COUNT}" -eq "${OUTBOX_TOTAL}" ] && echo "YES" || echo "NO") |
| \`order\` | Unpublished outbox events | ${OUTBOX_UNPUBLISHED} | 0 | $([ "${OUTBOX_UNPUBLISHED}" -eq 0 ] && echo "YES" || echo "NO") |
| \`inventory\` | \`inventory.reservations\` | ${INVENTORY_RESERVATIONS} | == ${ORDERS_COUNT} | $([ "${ORDERS_COUNT}" -eq "${INVENTORY_RESERVATIONS}" ] && echo "YES" || echo "NO") |
| \`order_query\` | \`order_query.order_read_model\` | ${READ_MODEL_COUNT} | == ${ORDERS_COUNT} | $([ "${ORDERS_COUNT}" -eq "${READ_MODEL_COUNT}" ] && echo "YES" || echo "NO") |
| \`order_query\` | Distinct Order IDs | ${READ_MODEL_DISTINCT} | == ${READ_MODEL_COUNT} | $([ "${READ_MODEL_COUNT}" -eq "${READ_MODEL_DISTINCT}" ] && echo "YES" || echo "NO") |
| \`kafka\` | \`order-placed-dlq\` Messages | ${DLQ_COUNT} | 0 | $([ "${DLQ_COUNT}" -eq 0 ] && echo "YES" || echo "NO") |

EOF

if [ "${STATUS}" = "FAIL" ]; then
  echo -e "Reconciliation FAILURES:${FAILURES}" >> "${OUTPUT_DIR}/reconciliation.md"
  echo "=== [DATA RECONCILIATION] FAILED ===" >&2
  echo -e "${FAILURES}" >&2
  exit 1
else
  echo "=== [DATA RECONCILIATION] PASSED (All ${ORDERS_COUNT} orders verified across outbox, inventory, and read model) ==="
fi
