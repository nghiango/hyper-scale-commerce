#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
for tool in kind kubectl helm docker openssl; do command -v "$tool" >/dev/null; done
context=kind-hyperscale-k8s
if ! kind get clusters | grep -qx hyperscale-k8s; then
  kind create cluster --name hyperscale-k8s --image kindest/node:v1.32.2 --config local-ci/kind-config.yaml --wait 180s
else
  # Existing clusters may lack the registry NodePort mapping; fail rather than recreate data.
  docker port hyperscale-k8s-worker 30500/tcp | grep -q '127.0.0.1:5001' || {
    echo 'Existing cluster lacks registry port mapping. Use a separately approved recreation or configure forwarding.' >&2; exit 1; }
fi
kubectl --context "$context" apply -f local-ci/registry.yaml
kubectl --context "$context" -n local-registry rollout status deploy/registry --timeout=180s
for node in $(kubectl --context "$context" get nodes -o jsonpath='{.items[*].metadata.name}'); do
  docker exec "$node" mkdir -p /etc/containerd/certs.d/hyperscale-k8s-worker:30500
  printf '%s\n' 'server = "http://hyperscale-k8s-worker:30500"' \
    '[host."http://hyperscale-k8s-worker:30500"]' '  capabilities = ["pull", "resolve", "push"]' | \
    docker exec -i "$node" /bin/sh -c 'cat > /etc/containerd/certs.d/hyperscale-k8s-worker:30500/hosts.toml'
done
if ! docker buildx inspect hyperscale-local >/dev/null 2>&1; then
  docker buildx create --name hyperscale-local --driver docker-container \
    --driver-opt network=kind --buildkitd-config local-ci/buildkitd.toml
fi
docker buildx inspect hyperscale-local --bootstrap
# Versioned upstream installs; all mutations name the intended context.
helm --kube-context "$context" upgrade --install argocd argo-cd \
  --repo https://argoproj.github.io/argo-helm --version 7.8.26 \
  --namespace argocd --create-namespace --wait --timeout 10m
helm --kube-context "$context" upgrade --install metrics-server metrics-server \
  --repo https://kubernetes-sigs.github.io/metrics-server/ --version 3.12.2 \
  --namespace kube-system --set 'args[0]=--kubelet-insecure-tls' --wait --timeout 5m
kubectl --context "$context" create namespace hyperscale --dry-run=client -o yaml | kubectl --context "$context" apply -f -
if ! kubectl --context "$context" -n hyperscale get secret local-platform-credentials >/dev/null 2>&1; then
  secret_dir=$(mktemp -d)
  trap 'rm -rf "$secret_dir"' EXIT
  openssl rand -hex 24 | tr -d '\n' > "$secret_dir/grafana-password"
  kubectl --context "$context" -n hyperscale create secret generic local-platform-credentials \
    --from-file=grafana-password="$secret_dir/grafana-password"
fi
# Bootstrap observability before the first GitOps release; Argo adopts these
# identical resources later. Never override it once the Application exists.
if ! kubectl --context "$context" -n argocd get application hyperscale-local-platform >/dev/null 2>&1; then
  helm template local-platform local-ci/platform | kubectl --context "$context" apply -f -
  for workload in prometheus otel-collector tempo grafana; do
    kubectl --context "$context" -n hyperscale rollout status "deployment/$workload" --timeout=180s
  done
fi
echo 'Registry ready at http://localhost:5001 (host), hyperscale-k8s-worker:30500 (Docker/kind).'
echo 'Next: configure Jenkins and initialize the GitOps repository; see docs/operations/local-cicd.md.'
