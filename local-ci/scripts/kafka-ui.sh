#!/usr/bin/env bash
set -euo pipefail
exec kubectl --context kind-hyperscale-k8s -n hyperscale port-forward --address 127.0.0.1 svc/kafka-ui 8082:8080
