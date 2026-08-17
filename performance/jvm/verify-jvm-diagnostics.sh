#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FIXTURE_DIR="${ROOT_DIR}/build/phase18/jvm-fixture"
BUNDLE_DIR="${ROOT_DIR}/build/phase18/jvm/fixture"
FIXTURE_LOG="${FIXTURE_DIR}/fixture.log"

for command in javac java jcmd; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "ERROR: Required JDK command '${command}' is unavailable." >&2
    exit 1
  }
done

mkdir -p "${FIXTURE_DIR}"
javac -d "${FIXTURE_DIR}" "${SCRIPT_DIR}/DiagnosticFixture.java"
java -cp "${FIXTURE_DIR}" DiagnosticFixture >"${FIXTURE_LOG}" 2>&1 &
FIXTURE_PID=$!

cleanup() {
  kill "${FIXTURE_PID}" >/dev/null 2>&1 || true
  wait "${FIXTURE_PID}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in $(seq 1 50); do
  if grep -q 'PHASE18_DIAGNOSTIC_FIXTURE_READY' "${FIXTURE_LOG}"; then break; fi
  if ! kill -0 "${FIXTURE_PID}" >/dev/null 2>&1; then
    echo "ERROR: Diagnostic fixture terminated before becoming ready." >&2
    exit 1
  fi
  sleep 0.1
done
grep -q 'PHASE18_DIAGNOSTIC_FIXTURE_READY' "${FIXTURE_LOG}"

"${SCRIPT_DIR}/capture-jvm-diagnostics.sh" \
  --local-pid "${FIXTURE_PID}" \
  --duration 2 \
  --output "${BUNDLE_DIR}"

grep -q 'Found one Java-level deadlock' "${BUNDLE_DIR}/thread-dump.txt"
grep -q 'phase18-leak-fixture-' "${BUNDLE_DIR}/thread-dump.txt"
grep -q '\[B' "${BUNDLE_DIR}/class-histogram.txt"
test -s "${BUNDLE_DIR}/recording.jfr"
test -s "${BUNDLE_DIR}/summary.md"

echo "Phase 18 JVM diagnostic fixture verification PASSED"
