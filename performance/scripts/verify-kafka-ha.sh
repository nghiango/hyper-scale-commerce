#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== [KAFKA-HA-VERIFY] Starting Kafka High Availability Qualification Test ==="

# 1. Run Preflight
bash "${SCRIPT_DIR}/preflight-kafka-ha.sh"

# 2. Test initial produce and consume with acks=all
echo "Testing end-to-end publish/consume on 3-broker cluster..."
TEST_PAYLOAD="ha-smoke-test-$(date +%s)"
echo "${TEST_PAYLOAD}" | docker exec -i hyperscale-kafka-1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic health-check \
  --producer-property acks=all

CONSUMED_OUTPUT=$(docker exec hyperscale-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic health-check \
  --from-beginning \
  --max-messages 1 \
  --timeout-ms 5000)

if [[ "${CONSUMED_OUTPUT}" != *"${TEST_PAYLOAD}"* ]]; then
  echo "ERROR: Initial publish/consume test failed. Output: ${CONSUMED_OUTPUT}" >&2
  exit 1
fi
echo "  -> Initial publish and consume succeeded (PASSED)"

# 3. Simulate Single Broker Loss (Stop kafka-2)
echo "Injecting fault: Stopping hyperscale-kafka-2..."
docker stop hyperscale-kafka-2

echo "Waiting 3 seconds for leader re-election..."
sleep 3

# 4. Verify Quorum & 0 Offline Partitions with 2 remaining brokers
echo "Verifying cluster state with broker 2 down..."
OFFLINE_PARTITIONS=$(docker exec hyperscale-kafka-1 kafka-topics --bootstrap-server localhost:9092 --describe --unavailable-partitions)
if [ -n "${OFFLINE_PARTITIONS}" ]; then
  echo "ERROR: Found offline partitions during single broker loss:" >&2
  echo "${OFFLINE_PARTITIONS}" >&2
  docker start hyperscale-kafka-2 || true
  exit 1
fi
echo "  -> 0 offline partitions during broker failure (PASSED)"

# 5. Produce with acks=all during single broker outage (min.isr=2 satisfied)
echo "Testing publish with acks=all during single broker outage (ISR=2, min.isr=2)..."
FAILOVER_PAYLOAD="ha-failover-test-$(date +%s)"
echo "${FAILOVER_PAYLOAD}" | docker exec -i hyperscale-kafka-1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic health-check \
  --producer-property acks=all

CONSUMED_FAILOVER=$(docker exec hyperscale-kafka-3 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic health-check \
  --from-beginning \
  --max-messages 2 \
  --timeout-ms 5000)

if [[ "${CONSUMED_FAILOVER}" != *"${FAILOVER_PAYLOAD}"* ]]; then
  echo "ERROR: Publish during failover failed. Output: ${CONSUMED_FAILOVER}" >&2
  docker start hyperscale-kafka-2 || true
  exit 1
fi
echo "  -> Publish during broker failure succeeded (PASSED)"

# 6. Restart Broker 2 and Wait for ISR Recovery
echo "Restarting hyperscale-kafka-2..."
docker start hyperscale-kafka-2

echo "Waiting for broker 2 to rejoin ISR (up to 30 seconds)..."
REJOINED=false
for attempt in $(seq 1 15); do
  UNDER_REPLICATED=$(docker exec hyperscale-kafka-1 kafka-topics --bootstrap-server localhost:9092 --describe --under-replicated-partitions || true)
  if [ -z "${UNDER_REPLICATED}" ]; then
    echo "  -> Broker 2 successfully rejoined ISR after ~${attempt} checks."
    REJOINED=true
    break
  fi
  sleep 2
done

if [ "${REJOINED}" != "true" ]; then
  echo "ERROR: Partitions remained under-replicated after 30s." >&2
  exit 1
fi

echo "=== [KAFKA-HA-VERIFY] Kafka 3-Node High Availability Qualification Test PASSED ==="
