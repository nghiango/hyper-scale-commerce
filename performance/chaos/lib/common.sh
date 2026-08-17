#!/usr/bin/env bash
set -euo pipefail

# ANSI color codes
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

TOXIPROXY_API="${TOXIPROXY_API:-http://127.0.0.1:8474}"
APP_URL="${APP_URL:-http://127.0.0.1:8080}"
ORDER_QUERY_URL="${ORDER_QUERY_URL:-http://127.0.0.1:8081}"

log_info() {
  printf "${BLUE}[INFO] %s${NC}\n" "$1"
}

log_success() {
  printf "${GREEN}[SUCCESS] %s${NC}\n" "$1"
}

log_warn() {
  printf "${YELLOW}[WARN] %s${NC}\n" "$1"
}

log_error() {
  printf "${RED}[ERROR] %s${NC}\n" "$1" >&2
}

# Blast radius guard: target validation
validate_target_safety() {
  local target="$1"
  case "$target" in
    hyperscale-app|hyperscale-order-query|hyperscale-postgres|hyperscale-kafka|hyperscale-toxiproxy|app|order-query|postgres|kafka|toxiproxy|\
    hyperscale-app-1|hyperscale-app-2|hyperscale-order-query-1|hyperscale-order-query-2|\
    hyperscale-kafka-1|hyperscale-kafka-2|hyperscale-kafka-3|hyperscale-haproxy|\
    hyperscale-postgres-1|hyperscale-postgres-2|hyperscale-postgres-3|\
    hyperscale-etcd-1|hyperscale-etcd-2|hyperscale-etcd-3|\
    app-1|app-2|order-query-1|order-query-2|kafka-1|kafka-2|kafka-3|haproxy|postgres-1|postgres-2|postgres-3|etcd-1|etcd-2|etcd-3)
      return 0
      ;;
    *)
      log_error "Target '$target' is NOT in the approved local container allow-list. Aborting."
      exit 1
      ;;
  esac
}

# Ensure toxiproxy is reachable
wait_for_toxiproxy() {
  local retries=15
  log_info "Checking Toxiproxy reachability at $TOXIPROXY_API..."
  while ! curl -fsS "$TOXIPROXY_API/version" >/dev/null 2>&1; do
    retries=$((retries - 1))
    if [ "$retries" -le 0 ]; then
      log_error "Toxiproxy is not reachable at $TOXIPROXY_API"
      exit 1
    fi
    sleep 1
  done
  log_info "Toxiproxy is ready."
}
