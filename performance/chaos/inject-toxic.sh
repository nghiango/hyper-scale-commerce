#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

usage() {
  cat <<EOF
Usage: $0 <command> [args...]

Commands:
  latency <proxy> <latency_ms> [jitter_ms] [toxic_name]
  bandwidth <proxy> <rate_kbps> [toxic_name]
  slicer <proxy> <avg_size> <size_var> <delay_us> [toxic_name]
  down <proxy>
  up <proxy>
  remove <proxy> <toxic_name>
  reset
EOF
  exit 1
}

if [ "$#" -lt 1 ]; then
  usage
fi

COMMAND="$1"
shift

case "$COMMAND" in
  latency)
    PROXY="$1"
    LATENCY_MS="$2"
    JITTER_MS="${3:-0}"
    TOXIC_NAME="${4:-latency_toxic}"
    log_info "Injecting latency toxic '$TOXIC_NAME' on proxy '$PROXY': ${LATENCY_MS}ms ± ${JITTER_MS}ms..."
    payload=$(printf '{"name":"%s","type":"latency","attributes":{"latency":%d,"jitter":%d}}' "$TOXIC_NAME" "$LATENCY_MS" "$JITTER_MS")
    curl -fsS -X POST -H "Content-Type: application/json" -d "$payload" "$TOXIPROXY_API/proxies/$PROXY/toxics"
    log_success "Latency toxic active on $PROXY."
    ;;

  bandwidth)
    PROXY="$1"
    RATE_KBPS="$2"
    TOXIC_NAME="${3:-bandwidth_toxic}"
    log_info "Injecting bandwidth limit toxic '$TOXIC_NAME' on proxy '$PROXY': ${RATE_KBPS} KB/s..."
    payload=$(printf '{"name":"%s","type":"bandwidth","attributes":{"rate":%d}}' "$TOXIC_NAME" "$RATE_KBPS")
    curl -fsS -X POST -H "Content-Type: application/json" -d "$payload" "$TOXIPROXY_API/proxies/$PROXY/toxics"
    log_success "Bandwidth limit toxic active on $PROXY."
    ;;

  slicer)
    PROXY="$1"
    AVG_SIZE="$2"
    SIZE_VAR="$3"
    DELAY_US="$4"
    TOXIC_NAME="${5:-slicer_toxic}"
    log_info "Injecting slicer toxic '$TOXIC_NAME' on proxy '$PROXY'..."
    payload=$(printf '{"name":"%s","type":"slicer","attributes":{"average_size":%d,"size_variation":%d,"delay":%d}}' "$TOXIC_NAME" "$AVG_SIZE" "$SIZE_VAR" "$DELAY_US")
    curl -fsS -X POST -H "Content-Type: application/json" -d "$payload" "$TOXIPROXY_API/proxies/$PROXY/toxics"
    log_success "Slicer toxic active on $PROXY."
    ;;

  down)
    PROXY="$1"
    log_info "Disabling proxy '$PROXY'..."
    curl -fsS -X POST -H "Content-Type: application/json" -d '{"enabled": false}' "$TOXIPROXY_API/proxies/$PROXY"
    log_success "Proxy $PROXY is now DOWN."
    ;;

  up)
    PROXY="$1"
    log_info "Enabling proxy '$PROXY'..."
    curl -fsS -X POST -H "Content-Type: application/json" -d '{"enabled": true}' "$TOXIPROXY_API/proxies/$PROXY"
    log_success "Proxy $PROXY is now UP."
    ;;

  remove)
    PROXY="$1"
    TOXIC_NAME="$2"
    log_info "Removing toxic '$TOXIC_NAME' from proxy '$PROXY'..."
    curl -fsS -X DELETE "$TOXIPROXY_API/proxies/$PROXY/toxics/$TOXIC_NAME"
    log_success "Toxic $TOXIC_NAME removed from $PROXY."
    ;;

  reset)
    bash "$SCRIPT_DIR/cleanup-chaos.sh"
    ;;

  *)
    usage
    ;;
esac
