#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

query_tip_with_timeout() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "${DEVNET_QUERY_TIMEOUT_SECONDS:-15}s" "$DEVNET_DIR/scripts/status.sh"
  else
    query_tip_json
  fi
}

report_startup_failure() {
  local node_log
  echo "Cardano devnet startup diagnostics (host UTC $(date -u +%Y-%m-%dT%H:%M:%SZ)):" >&2
  echo "Configured pool nodes: ${CARDANO_TESTNET_NUM_POOL_NODES:-1}" >&2
  if [ -f "$TESTNET_DIR/shelley-genesis.json" ]; then
    echo "Generated genesis timing and active slots:" >&2
    grep -E '"(activeSlotsCoeff|epochLength|slotLength|systemStart)"' \
      "$TESTNET_DIR/shelley-genesis.json" >&2 || true
  fi
  if have_command docker; then
    docker_compose ps --all >&2 || true
    docker_compose logs --no-color --tail=120 cardano-node >&2 || true
  fi
  node_log="$TESTNET_DIR/logs/node1/stdout.log"
  if [ -f "$node_log" ]; then
    echo "Cardano node1 clock, forge, consensus, and warning traces (full log: $node_log):" >&2
    grep -E '"ns":"(BlockchainTime|Forge|Consensus\.)|"sev":"(Warning|Error|Critical)"' \
      "$node_log" | tail -80 >&2 || true
    if ! grep -q '"ns":"Forge\.' "$node_log"; then
      echo "No Forge traces were recorded for node1." >&2
    fi
  fi
  if [ -f "$RUNTIME_DIR/cardano-testnet.log" ]; then
    tail -80 "$RUNTIME_DIR/cardano-testnet.log" >&2 || true
  fi
}

previous_slot=""
last_observed_slot="unavailable"
health_attempts="${DEVNET_HEALTH_ATTEMPTS:-120}"
for attempt in $(seq 1 "$health_attempts"); do
  if have_command docker && ! docker_compose ps --status running --services | grep -qx 'cardano-node'; then
    echo "Cardano devnet container is not running." >&2
    report_startup_failure
    exit 1
  fi
  if { [ -S "$SOCKET_PATH" ] || [ -e "$SOCKET_PATH" ] || ! have_command cardano-cli; } && tip="$(query_tip_with_timeout 2>/dev/null)"; then
    slot="$(printf '%s' "$tip" | extract_json_number slot | tail -1)"
    if [ -n "$slot" ]; then last_observed_slot="$slot"; fi
    if [ -n "$slot" ] && [ -n "$previous_slot" ] && [ "$slot" -gt "$previous_slot" ]; then
      echo "$tip"
      exit 0
    fi
    previous_slot="$slot"
  fi
  if (( attempt % 10 == 0 )); then
    echo "Waiting for Cardano blocks: attempt $attempt/$health_attempts, last observed slot: $last_observed_slot" >&2
  fi
  sleep 2
done

echo "Cardano devnet did not produce advancing blocks in time." >&2
report_startup_failure
exit 1
