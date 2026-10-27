#!/usr/bin/env bash
set -euo pipefail
command -v k9s >/dev/null
exec k9s --context kind-hyperscale-k8s --namespace hyperscale --readonly "$@"
