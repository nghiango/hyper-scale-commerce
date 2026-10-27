#!/usr/bin/env bash
set -euo pipefail
: "${JENKINS_AGENT_SECRET:?Set the inbound agent secret from Jenkins}"
curl --fail --retry 12 --retry-delay 5 -o /tmp/agent.jar http://jenkins:8080/jnlpJars/agent.jar
# Pass the secret via a file, not a process-list argument.
printf '%s' "$JENKINS_AGENT_SECRET" > /tmp/agent-secret
chmod 600 /tmp/agent-secret
unset JENKINS_AGENT_SECRET
exec java -jar /tmp/agent.jar -url http://jenkins:8080/ -secret @/tmp/agent-secret \
  -name local-arm64 -webSocket -workDir /workspace
