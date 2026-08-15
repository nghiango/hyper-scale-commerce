#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

wait_for_toxiproxy

log_info "Initializing Toxiproxy proxies..."

create_or_update_proxy() {
  local name="$1"
  local listen="$2"
  local upstream="$3"

  # Delete existing proxy if present
  curl -fsS -X DELETE "$TOXIPROXY_API/proxies/$name" >/dev/null 2>&1 || true

  # Create proxy
  local payload
  payload=$(printf '{"name":"%s","listen":"%s","upstream":"%s","enabled":true}' "$name" "$listen" "$upstream")
  
  local response
  response=$(curl -fsS -X POST -H "Content-Type: application/json" -d "$payload" "$TOXIPROXY_API/proxies")
  log_info "Configured proxy: $name ($listen -> $upstream)"
}

create_or_update_proxy "app_postgres" "0.0.0.0:5432" "postgres:5432"
create_or_update_proxy "orderquery_postgres" "0.0.0.0:5433" "postgres:5432"
create_or_update_proxy "kafka" "0.0.0.0:9092" "kafka:9092"

log_success "All proxies successfully initialized."
