#!/bin/bash
set -e

echo "============================================"
echo " Distributed Clock Sync rApp"
echo " Node Type: ${NODE_TYPE}"
echo " Node ID:   ${NODE_ID}"
echo " HTTP Port: ${HTTP_PORT}"
echo " OSC Port:  ${OSC_PORT}"
echo " Genesis:   ${GENESIS_HOST}"
echo " BPM:       ${INITIAL_BPM}"
echo "============================================"

# Wait for genesis node if we're a validator
if [ "${NODE_TYPE}" != "genesis" ]; then
  echo "Waiting for genesis node at ${GENESIS_HOST}:9000..."
  until curl -sf "http://${GENESIS_HOST}:9000/health" > /dev/null 2>&1; do
    echo "  Genesis not ready, retrying in 2s..."
    sleep 2
  done
  echo "Genesis node is ready!"
fi

exec java \
  ${JAVA_OPTS} \
  -DNODE_TYPE="${NODE_TYPE}" \
  -DHTTP_PORT="${HTTP_PORT}" \
  -DOSC_PORT="${OSC_PORT}" \
  -DGENESIS_HOST="${GENESIS_HOST}" \
  -DNODE_ID="${NODE_ID}" \
  -DINITIAL_BPM="${INITIAL_BPM}" \
  -DWASM_PATH="${WASM_PATH}" \
  -DPROOF_DIR="${PROOF_DIR}" \
  -jar /app/distributed-clock-sync.jar
