#!/usr/bin/env bash
set -euo pipefail
: "${GITOPS_URL:?}" "${GITOPS_BRANCH:?}" "${SSH_AUTH_SOCK:?Jenkins SSH agent credential required}" "${IMAGE_PREFIX:?}" "${REVISION:?}"
[[ "$GITOPS_URL" == git@github.com:* ]]
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf -v GIT_SSH_COMMAND 'ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=%q' "$PWD/local-ci/jenkins/github_known_hosts"
export GIT_SSH_COMMAND GIT_TERMINAL_PROMPT=0
heads=$(git ls-remote --heads "$GITOPS_URL")
if [[ -z "$heads" ]]; then
  git clone "$GITOPS_URL" "$work/repo"
  git -C "$work/repo" checkout --orphan "$GITOPS_BRANCH"
else
  git clone --single-branch --branch "$GITOPS_BRANCH" "$GITOPS_URL" "$work/repo"
fi
python3 local-ci/scripts/release.py "$work/repo" "$IMAGE_PREFIX" "$REVISION"
helm lint "$work/repo/charts/commerce" -f "$work/repo/environments/local/values.yaml"
helm lint "$work/repo/charts/platform"
git -C "$work/repo" add charts environments/local/values.yaml
if git -C "$work/repo" diff --cached --quiet; then exit 0; fi
git -C "$work/repo" -c user.name='Jenkins local CI' -c user.email='jenkins@localhost' commit -m "Deploy $REVISION (linux/arm64)"
git -C "$work/repo" push origin "HEAD:$GITOPS_BRANCH"
