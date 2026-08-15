#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

trap 'bash "$SCRIPT_DIR/cleanup-chaos.sh"' EXIT INT TERM

log_info "=== Starting Chaos Preflight Validation ==="

# 1. Verify Toxiproxy API
wait_for_toxiproxy

# 2. Initialize Proxies
bash "$SCRIPT_DIR/init-proxies.sh"

# 3. Test safety target allow-list validation
log_info "Testing safety target validation..."
if validate_target_safety "hyperscale-app" && validate_target_safety "hyperscale-toxiproxy"; then
  log_info "Allowed targets passed validation."
fi

# 4. Verify baseline service readiness
log_info "Checking baseline health of app (:8080) and order-query (:8081)..."
for url in "$APP_URL" "$ORDER_QUERY_URL"; do
  retries=30
  while ! curl -fsS "$url/actuator/health/readiness" >/dev/null 2>&1; do
    retries=$((retries - 1))
    if [ "$retries" -le 0 ]; then
      log_error "Service at $url failed to become ready."
      exit 1
    fi
    sleep 1
  done
done
log_success "Baseline health verified."

# 5. Test toxic injection & cleanup
log_info "Testing latency toxic injection on app_postgres proxy..."
bash "$SCRIPT_DIR/inject-toxic.sh" latency app_postgres 100 10 preflight_test
bash "$SCRIPT_DIR/inject-toxic.sh" remove app_postgres preflight_test
log_success "Latency toxic injection and removal verified."

log_success "=== Chaos Preflight Validation PASSED ==="
