#!/usr/bin/env bash
# Manage background kubectl port-forwards for the local UIs.
# Forwards die whenever the kind nodes stop; restart them after local-stack start.
set -euo pipefail

CONTEXT="kind-hyperscale-k8s"
RUN_DIR="${TMPDIR:-/tmp}/hyperscale-local-ui"

# name | namespace | resource | local:remote | url
FORWARDS=(
  "grafana|hyperscale|svc/grafana|3000:3000|http://localhost:3000"
  "prometheus|hyperscale|svc/prometheus|9090:9090|http://localhost:9090"
  "kafka-ui|hyperscale|svc/kafka-ui|8082:8080|http://localhost:8082"
  "argocd|argocd|svc/argocd-server|8443:443|https://localhost:8443"
  "postgres|hyperscale|svc/postgres-ha-primary|5433:5432|postgresql://localhost:5433"
)

usage() {
  cat <<'HELP'
Usage: make local-ui          (same as: bash local-ci/scripts/local-ui.sh start)
       bash local-ci/scripts/local-ui.sh [start|stop|restart|status]

Starts kubectl port-forward in the background for each local UI, bound to
127.0.0.1 only. Logs and PID files live under $TMPDIR/hyperscale-local-ui.

  Grafana     http://localhost:3000   (admin / local-platform-credentials grafana-password)
  Prometheus  http://localhost:9090
  Kafka UI    http://localhost:8082
  Argo CD     https://localhost:8443  (admin / argocd-initial-admin-secret)
  PostgreSQL  postgresql://localhost:5433  (hyperscale/hyperscale, Patroni primary;
              5433 avoids the Compose Postgres on 5432)

Port-forwards are client-side processes. They die when the kind containers
stop, so rerun this after 'make local-stack' → start.
HELP
}

pid_file() { echo "${RUN_DIR}/$1.pid"; }

is_running() {
  local pidfile; pidfile=$(pid_file "$1")
  [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null
}

stop_one() {
  local name=$1 port=$2 pidfile pid holder
  pidfile=$(pid_file "$name")
  if [[ -f "$pidfile" ]]; then
    pid=$(cat "$pidfile")
    kill "$pid" 2>/dev/null || true
    rm -f "$pidfile"
  fi
  # Kill any stray kubectl still holding the local port.
  for holder in $(lsof -ti ":${port}" 2>/dev/null || true); do
    if ps -p "$holder" -o comm= | grep -q kubectl; then
      kill "$holder" 2>/dev/null || true
    fi
  done
}

do_stop() {
  local name ns svc ports url
  for entry in "${FORWARDS[@]}"; do
    IFS='|' read -r name ns svc ports url <<< "$entry"
    stop_one "$name" "${ports%%:*}"
  done
  echo 'All UI port-forwards stopped.'
}

do_start() {
  mkdir -p "$RUN_DIR"
  command -v kubectl >/dev/null || { echo 'kubectl is required.' >&2; exit 1; }
  kubectl --context "$CONTEXT" --request-timeout=5s get --raw=/readyz >/dev/null 2>&1 \
    || { echo "Kubernetes API not reachable on context ${CONTEXT}. Run 'make local-stack' first." >&2; exit 1; }

  local name ns svc ports url
  for entry in "${FORWARDS[@]}"; do
    IFS='|' read -r name ns svc ports url <<< "$entry"
    if is_running "$name"; then
      printf '%-11s already running (pid %s)  %s\n' "$name" "$(cat "$(pid_file "$name")")" "$url"
      continue
    fi
    stop_one "$name" "${ports%%:*}"
    nohup kubectl --context "$CONTEXT" -n "$ns" port-forward --address 127.0.0.1 \
      "$svc" "$ports" > "${RUN_DIR}/${name}.log" 2>&1 &
    echo $! > "$(pid_file "$name")"
  done

  sleep 2
  local failed=0
  for entry in "${FORWARDS[@]}"; do
    IFS='|' read -r name ns svc ports url <<< "$entry"
    if is_running "$name"; then
      printf '%-11s %-24s %s\n' "$name" "up →" "$url"
    else
      failed=1
      printf '%-11s FAILED — see %s\n' "$name" "${RUN_DIR}/${name}.log"
    fi
  done
  [[ "$failed" -eq 0 ]] || exit 1
}

do_status() {
  local name ns svc ports url
  for entry in "${FORWARDS[@]}"; do
    IFS='|' read -r name ns svc ports url <<< "$entry"
    if is_running "$name"; then
      printf '%-11s running (pid %s)  %s\n' "$name" "$(cat "$(pid_file "$name")")" "$url"
    elif lsof -ti ":${ports%%:*}" >/dev/null 2>&1; then
      printf '%-11s port %s held by another process  %s\n' "$name" "${ports%%:*}" "$url"
    else
      printf '%-11s stopped  %s\n' "$name" "$url"
    fi
  done
}

case "${1:-start}" in
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop; do_start ;;
  status)  do_status ;;
  -h|--help) usage ;;
  *) usage >&2; exit 2 ;;
esac
