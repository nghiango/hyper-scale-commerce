#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -d "$1" ]; then
  echo "Usage: $0 BUNDLE_DIRECTORY" >&2
  exit 2
fi

BUNDLE_DIR="$(cd "$1" && pwd)"
THREAD_DUMP="${BUNDLE_DIR}/thread-dump.txt"
JFR_FILE="${BUNDLE_DIR}/recording.jfr"
SUMMARY_FILE="${BUNDLE_DIR}/summary.md"

for required in "${BUNDLE_DIR}/metadata.txt" "${THREAD_DUMP}" "${BUNDLE_DIR}/heap-info.txt" "${BUNDLE_DIR}/class-histogram.txt" "${JFR_FILE}"; do
  if [ ! -s "${required}" ]; then
    echo "ERROR: Required diagnostic artifact is absent or empty: ${required}" >&2
    exit 1
  fi
done

DEADLOCKS="$(grep -c 'Found one Java-level deadlock' "${THREAD_DUMP}" || true)"
RUNNABLE="$(grep -c 'java.lang.Thread.State: RUNNABLE' "${THREAD_DUMP}" || true)"
BLOCKED="$(grep -c 'java.lang.Thread.State: BLOCKED' "${THREAD_DUMP}" || true)"
WAITING="$(grep -Ec 'java.lang.Thread.State: (WAITING|TIMED_WAITING)' "${THREAD_DUMP}" || true)"
THREADS="$(grep -c '^"' "${THREAD_DUMP}" || true)"

JFR_SUMMARY_STATUS="unavailable"
PINNED_EVENTS="not-measured"
if command -v jfr >/dev/null 2>&1; then
  jfr summary "${JFR_FILE}" >"${BUNDLE_DIR}/jfr-summary.txt"
  JFR_SUMMARY_STATUS="captured"
  PINNED_EVENTS="$(awk '$1 == "jdk.VirtualThreadPinned" {print $2}' "${BUNDLE_DIR}/jfr-summary.txt" | tail -1)"
  PINNED_EVENTS="${PINNED_EVENTS:-0}"
fi

cat >"${SUMMARY_FILE}" <<EOF
# JVM Diagnostic Bundle Summary

- Raw bundle: local ignored build artifact
- JFR summary: ${JFR_SUMMARY_STATUS}
- Threads observed: ${THREADS}
- RUNNABLE: ${RUNNABLE}
- BLOCKED: ${BLOCKED}
- WAITING/TIMED_WAITING: ${WAITING}
- Java-level deadlocks: ${DEADLOCKS}
- Virtual-thread pinned events: ${PINNED_EVENTS}

## Safety

Raw system properties, class histograms, thread dumps, and JFR recordings may
contain sensitive values. They must remain under ignored build storage and
must not be committed or uploaded without an explicit redaction review.
EOF

echo "Diagnostic summary: ${SUMMARY_FILE}"
