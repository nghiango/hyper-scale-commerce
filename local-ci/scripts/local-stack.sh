#!/usr/bin/env bash
# Pause/resume existing project containers; never recreate containers or volumes.
set -euo pipefail

usage() {
  cat <<'HELP'
Usage: make local-stack
       bash local-ci/scripts/local-stack.sh [start|stop|status] [--dry-run]

Without arguments, opens an interactive menu.

Targets only the hyperscale-k8s kind cluster, hyperscale-local-ci Compose project,
and buildx_buildkit_hyperscale-local0 builder. Docker must already be running.
Stop/start preserves existing containers and volumes. Finish Jenkins builds first.
Start waits for the Kubernetes API, nodes and local registry before starting CI.
Restart UI port-forwards separately after starting the stack.
HELP
}
menu() {
  local choice
  local -a selected
  while true; do
    cat <<'MENU'

Local project containers
  1) Start stack
  2) Stop stack (finish Jenkins builds first)
  3) Show status
  4) Preview start (dry-run)
  5) Preview stop (dry-run)
  0) Exit
MENU
    printf 'Choose an option: '
    if ! IFS= read -r choice; then printf '\n'; return 0; fi
    case "$choice" in
      1) selected=(start) ;;
      2) selected=(stop) ;;
      3) selected=(status) ;;
      4) selected=(start --dry-run) ;;
      5) selected=(stop --dry-run) ;;
      0|q|Q) return 0 ;;
      *) echo 'Invalid option. Choose 0–5.'; continue ;;
    esac
    if bash "${BASH_SOURCE[0]}" "${selected[@]}"; then
      :
    else
      echo 'The operation failed. Review the error above before retrying.' >&2
    fi
  done
}
if [[ $# -eq 0 ]]; then menu; exit 0; fi
action=${1:-}
case "$action" in start|stop|status) ;; -h|--help) usage; exit 0 ;; *) usage >&2; exit 2 ;; esac
shift
dry_run=false
if [[ "${1:-}" == --dry-run ]]; then dry_run=true; shift; fi
if [[ $# -ne 0 ]]; then usage >&2; exit 2; fi
command -v docker >/dev/null || { echo 'Docker CLI is required.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Start Docker Desktop first.' >&2; exit 1; }

# Read names through Docker's project labels, not broad name-prefix matches.
collect() {
  local output name
  output=$(docker ps -a --filter "$1" --format '{{.Names}}')
  containers=()
  while IFS= read -r name; do
    if [[ -n "$name" ]]; then containers+=("$name"); fi
  done <<< "$output"
}
collect 'label=io.x-k8s.kind.cluster=hyperscale-k8s'
cluster=(${containers[@]+"${containers[@]}"})
collect 'label=com.docker.compose.project=hyperscale-local-ci'
ci=(${containers[@]+"${containers[@]}"})
collect 'name=^/buildx_buildkit_hyperscale-local0$'
builder=(${containers[@]+"${containers[@]}"})

run() {
  if [[ "$dry_run" == true ]]; then
    printf 'Would run: '
    printf '%q ' "$@"
    printf '\n'
  else
    "$@"
  fi
}

if [[ "$action" == status ]]; then
  for name in ${cluster[@]+"${cluster[@]}"} ${ci[@]+"${ci[@]}"} ${builder[@]+"${builder[@]}"}; do
    docker inspect --format '{{.Name}}: {{.State.Status}}' "$name"
  done
  exit 0
fi

if [[ "$action" == stop ]]; then
  if [[ ${#ci[@]} -gt 0 ]]; then run docker stop --time 60 "${ci[@]}"; fi
  if [[ ${#builder[@]} -gt 0 ]]; then run docker stop --time 60 "${builder[@]}"; fi
  if [[ ${#cluster[@]} -gt 0 ]]; then run docker stop --time 60 "${cluster[@]}"; fi
  echo 'Stop complete. Containers and volumes retained.'
  exit 0
fi

if [[ ${#cluster[@]} -eq 0 ]]; then
  echo 'No hyperscale-k8s containers found. This command resumes an existing stack; it does not bootstrap a cluster.' >&2
  exit 1
fi
if [[ "$dry_run" != true ]]; then
  command -v kubectl >/dev/null || { echo 'kubectl is required for startup readiness checks.' >&2; exit 1; }
fi
run docker start "${cluster[@]}"
if [[ "$dry_run" == true ]]; then
  echo 'Would wait for Kubernetes API readiness (up to 180s).'
else
  deadline=$((SECONDS + 180))
  until kubectl --context kind-hyperscale-k8s --request-timeout=5s get --raw=/readyz >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo 'Kubernetes API did not become ready; CI remains stopped. Inspect node containers and rerun start.' >&2
      exit 1
    fi
    sleep 2
  done
fi
run kubectl --context kind-hyperscale-k8s wait --for=condition=Ready nodes --all --timeout=180s
run kubectl --context kind-hyperscale-k8s -n local-registry rollout status deployment/registry --timeout=180s
if [[ ${#builder[@]} -gt 0 ]]; then run docker start "${builder[@]}"; fi
if [[ ${#ci[@]} -gt 0 ]]; then run docker start "${ci[@]}"; fi
echo 'Start complete. Check workload readiness and restart your UI port-forwards.'
