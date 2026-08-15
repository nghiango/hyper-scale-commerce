#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

TARGET_COUNT="${1:-1000}"

echo "=== [SEED DATA] Checking/Seeding Deterministic Catalog Data (Target: ${TARGET_COUNT} products) ==="

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-hyperscale-postgres}"
POSTGRES_USER="${POSTGRES_USER:-hyperscale}"
POSTGRES_DB="${POSTGRES_DB:-hyperscale}"

CURRENT_COUNT=$(docker exec "${POSTGRES_CONTAINER}" psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -t -A -c "SELECT COUNT(*) FROM catalog.products;" 2>/dev/null || echo "0")
echo "Current catalog product count: ${CURRENT_COUNT}"

if [ "${CURRENT_COUNT}" -ge "${TARGET_COUNT}" ]; then
  echo "Catalog data already satisfies target count (${CURRENT_COUNT} >= ${TARGET_COUNT}). Skipping seed."
  exit 0
fi

echo "Generating ${TARGET_COUNT} catalog products..."

docker exec -i "${POSTGRES_CONTAINER}" psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" <<SQL
DO \$\$
DECLARE
    i INT;
    sku_val VARCHAR;
    name_val VARCHAR;
    desc_val VARCHAR;
    price_val INT;
    keywords VARCHAR[] := ARRAY['Alpha','Bravo','Charlie','Delta','Echo','Foxtrot','Golf','Hotel','India','Juliet'];
BEGIN
    FOR i IN 1..${TARGET_COUNT} LOOP
        sku_val := 'PERF-SKU-' || LPAD(i::TEXT, 5, '0');
        name_val := 'Performance Product ' || i || ' with ' || keywords[1 + (i % 10)];
        desc_val := 'A performance test product for ' || keywords[1 + (i % 10)];
        price_val := 1000 + (i % 1000);

        INSERT INTO catalog.products (sku, name, description, price, availability, created_at, updated_at)
        VALUES (sku_val, name_val, desc_val, price_val, 'IN_STOCK', NOW(), NOW())
        ON CONFLICT (sku) DO NOTHING;
    END LOOP;
END \$\$;
SQL

NEW_COUNT=$(docker exec "${POSTGRES_CONTAINER}" psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -t -A -c "SELECT COUNT(*) FROM catalog.products;")
echo "=== [SEED DATA] Finished. Current product count: ${NEW_COUNT} ==="
