#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MODE=""
TARGET=""
CONTAINER=""
NAMESPACE="hyperscale"
CONTEXT="${KUBE_CONTEXT:-kind-hyperscale-k8s}"
DURATION_SECONDS="30"
OUTPUT_DIR=""

usage() {
  echo "Usage: $0 (--local-pid PID | --pod POD --container CONTAINER) [--namespace NS] [--context CONTEXT] [--duration SECONDS] [--output DIR]" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: Required command '$1' is unavailable." >&2
    exit 1
  }
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --local-pid)
      MODE="local"
      TARGET="${2:-}"
      shift 2
      ;;
    --pod)
      MODE="pod"
      TARGET="${2:-}"
      shift 2
      ;;
    --container)
      CONTAINER="${2:-}"
      shift 2
      ;;
    --namespace)
      NAMESPACE="${2:-}"
      shift 2
      ;;
    --context)
      CONTEXT="${2:-}"
      shift 2
      ;;
    --duration)
      DURATION_SECONDS="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="${2:-}"
      shift 2
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

if [ -z "${MODE}" ] || [ -z "${TARGET}" ]; then
  usage
  exit 2
fi
if ! [[ "${DURATION_SECONDS}" =~ ^[1-9][0-9]*$ ]] || [ "${DURATION_SECONDS}" -gt 300 ]; then
  echo "ERROR: --duration must be an integer from 1 to 300 seconds." >&2
  exit 2
fi
if [ "${MODE}" = "local" ] && ! [[ "${TARGET}" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: --local-pid requires an explicit numeric PID." >&2
  exit 2
fi
if [ "${MODE}" = "pod" ] && [ -z "${CONTAINER}" ]; then
  echo "ERROR: --container is required with --pod." >&2
  exit 2
fi

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
if [ -z "${OUTPUT_DIR}" ]; then
  OUTPUT_DIR="${ROOT_DIR}/build/phase18/jvm/${MODE}-${TARGET}-${TIMESTAMP}"
fi
mkdir -p "${OUTPUT_DIR}"
umask 077

cat >"${OUTPUT_DIR}/metadata.txt" <<EOF
timestamp=${TIMESTAMP}
mode=${MODE}
target=${TARGET}
container=${CONTAINER}
namespace=${NAMESPACE}
context=${CONTEXT}
duration_seconds=${DURATION_SECONDS}
git_revision=$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || echo unknown)
git_dirty=$(if git -C "${ROOT_DIR}" diff --quiet && git -C "${ROOT_DIR}" diff --cached --quiet; then echo false; else echo true; fi)
EOF

if [ "${MODE}" = "local" ]; then
  require_command jcmd
  if ! jcmd -l | awk '{print $1}' | grep -Fxq "${TARGET}"; then
    echo "ERROR: PID ${TARGET} is not an attachable local JVM." >&2
    exit 1
  fi

  jcmd "${TARGET}" VM.version >"${OUTPUT_DIR}/vm-version.txt"
  jcmd "${TARGET}" VM.flags >"${OUTPUT_DIR}/vm-flags.txt"
  jcmd "${TARGET}" VM.system_properties >"${OUTPUT_DIR}/system-properties.raw.txt"
  jcmd "${TARGET}" Thread.print -l >"${OUTPUT_DIR}/thread-dump.txt"
  jcmd "${TARGET}" GC.heap_info >"${OUTPUT_DIR}/heap-info.txt"
  jcmd "${TARGET}" GC.class_histogram >"${OUTPUT_DIR}/class-histogram.txt"
  jcmd "${TARGET}" VM.native_memory summary >"${OUTPUT_DIR}/native-memory.txt" 2>&1 || true
  JFR_PATH="${OUTPUT_DIR}/recording.jfr"
  jcmd "${TARGET}" JFR.start name=phase18 settings=profile \
    "duration=${DURATION_SECONDS}s" "filename=${JFR_PATH}" >/dev/null
  sleep "$((DURATION_SECONDS + 1))"
else
  require_command kubectl
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" get pod "${TARGET}" >/dev/null
  POD_JFR_PATH="/tmp/phase18-${TIMESTAMP}.jfr"
  kube_exec=(kubectl --context "${CONTEXT}" -n "${NAMESPACE}" exec "${TARGET}" -c "${CONTAINER}" --)

  "${kube_exec[@]}" jcmd 1 VM.version >"${OUTPUT_DIR}/vm-version.txt"
  "${kube_exec[@]}" jcmd 1 VM.flags >"${OUTPUT_DIR}/vm-flags.txt"
  "${kube_exec[@]}" jcmd 1 VM.system_properties >"${OUTPUT_DIR}/system-properties.raw.txt"
  "${kube_exec[@]}" jcmd 1 Thread.print -l >"${OUTPUT_DIR}/thread-dump.txt"
  "${kube_exec[@]}" jcmd 1 GC.heap_info >"${OUTPUT_DIR}/heap-info.txt"
  "${kube_exec[@]}" jcmd 1 GC.class_histogram >"${OUTPUT_DIR}/class-histogram.txt"
  "${kube_exec[@]}" jcmd 1 VM.native_memory summary >"${OUTPUT_DIR}/native-memory.txt" 2>&1 || true
  "${kube_exec[@]}" jcmd 1 JFR.start name=phase18 settings=profile \
    "duration=${DURATION_SECONDS}s" "filename=${POD_JFR_PATH}" >/dev/null
  sleep "$((DURATION_SECONDS + 1))"
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" cp \
    "${TARGET}:${POD_JFR_PATH}" "${OUTPUT_DIR}/recording.jfr" -c "${CONTAINER}"
  "${kube_exec[@]}" rm -f "${POD_JFR_PATH}"
fi

if [ ! -s "${OUTPUT_DIR}/recording.jfr" ]; then
  echo "ERROR: JFR recording was not created." >&2
  exit 1
fi

"${SCRIPT_DIR}/summarize-jvm-diagnostics.sh" "${OUTPUT_DIR}"
echo "JVM diagnostic bundle: ${OUTPUT_DIR}"
