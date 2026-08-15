#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

log_info "=== Injecting Concurrent Poison Messages across all 3 Kafka Partitions ==="

# Define 3 distinct poison payloads (unclosed JSON, invalid schema, corrupt binary text)
POISON_STREAM=$(cat <<'EOF'
1:{"version":1,"orderId":"MALFORMED_UNCLOSED_JSON
2:{"unexpected_schema_root":true,"data":"invalid_payload"}
3:CORRUPT_RAW_BINARY_TEXT_$$$###@@@
EOF
)

log_info "Publishing 3 poison records to topic 'order-placed' via kafka-console-producer..."

printf "%s\n" "$POISON_STREAM" | docker exec -i hyperscale-kafka \
  kafka-console-producer \
  --bootstrap-server localhost:29092 \
  --topic order-placed \
  --property "parse.key=true" \
  --property "key.separator=:"

log_success "Poison stream published successfully."
