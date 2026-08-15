#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

log_info "Cleaning up chaos: removing all toxics and re-enabling all proxies..."

if curl -fsS "$TOXIPROXY_API/version" >/dev/null 2>&1; then
  # Reset toxics
  curl -fsS -X POST "$TOXIPROXY_API/reset" >/dev/null 2>&1 || true

  # Ensure all known proxies are enabled
  for proxy in app_postgres orderquery_postgres kafka; do
    curl -fsS -X POST -H "Content-Type: application/json" \
      -d '{"enabled": true}' \
      "$TOXIPROXY_API/proxies/$proxy" >/dev/null 2>&1 || true
  done
  log_success "Toxiproxy state cleaned and proxies re-enabled."
else
  log_warn "Toxiproxy is not running; skipping proxy reset."
fi
