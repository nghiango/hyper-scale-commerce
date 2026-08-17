#!/usr/bin/env bash
set -euo pipefail

PRIMARY_NODE=""
PRIMARY_PORT=""

for port in 8008 8009 8010; do
  resp=$(curl -s "http://localhost:${port}/patroni" 2>/dev/null || true)
  role=$(echo "${resp}" | jq -r '.role // empty' 2>/dev/null || true)
  if [ "${role}" = "primary" ] || [ "${role}" = "master" ]; then
    PRIMARY_NODE=$(echo "${resp}" | jq -r '.patroni.name')
    case "${PRIMARY_NODE}" in
      postgres-1) PRIMARY_PORT=5432 ;;
      postgres-2) PRIMARY_PORT=5433 ;;
      postgres-3) PRIMARY_PORT=5434 ;;
      *) PRIMARY_PORT=5432 ;;
    esac
    break
  fi
done

if [ -z "${PRIMARY_NODE}" ]; then
  echo "ERROR: No active Patroni primary found." >&2
  exit 1
fi

echo "${PRIMARY_NODE}:${PRIMARY_PORT}"
