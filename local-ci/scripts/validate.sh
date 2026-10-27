#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
for file in local-ci/scripts/*.sh; do bash -n "$file"; done
helm lint performance/helm/hyperscale-commerce
helm lint local-ci/platform
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 local-ci/scripts/release.py "$work" hyperscale-k8s-worker:30500/hyperscale 0123456789012345678901234567890123456789
helm template hyperscale "$work/charts/commerce" -f "$work/environments/local/values.yaml" > "$work/commerce.yaml"
helm template platform "$work/charts/platform" > "$work/platform.yaml"
python3 local-ci/scripts/verify-release.py "$work"
python3 local-ci/scripts/test-gitops.py
