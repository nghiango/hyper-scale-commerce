#!/usr/bin/env bash
set -euo pipefail
: "${REGISTRY:?}" "${IMAGE_PREFIX:?}" "${REVISION:?}"
[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]]
[[ "$IMAGE_PREFIX" == "$REGISTRY/"* ]]
if ! docker buildx inspect hyperscale-local >/dev/null 2>&1; then
  docker buildx create --name hyperscale-local --node hyperscale-local0 \
    --driver docker-container --driver-opt network=kind \
    --buildkitd-config local-ci/buildkitd.toml
fi
docker buildx inspect hyperscale-local --bootstrap >/dev/null
output_flag=(--load)
if [[ "${PUSH_IMAGES:-false}" == "true" ]]; then
  output_flag=(--push)
fi
for module in app order-query; do
  docker buildx build --builder hyperscale-local --platform linux/arm64 "${output_flag[@]}" \
    --tag "$IMAGE_PREFIX/hyperscale-$module:$REVISION" "$module"
done
docker buildx build --builder hyperscale-local --platform linux/arm64 "${output_flag[@]}" \
  --tag "$IMAGE_PREFIX/hyperscale-patroni:$REVISION" performance/patroni
