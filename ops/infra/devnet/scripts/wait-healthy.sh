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

previous_slot=""
for _ in $(seq 1 "${DEVNET_HEALTH_ATTEMPTS:-120}"); do
  if have_command docker && ! docker_compose ps --status running --services | grep -qx 'cardano-node'; then
    echo "Cardano devnet container is not running." >&2
    docker_compose ps >&2 || true
    docker_compose logs --no-color --tail=120 cardano-node >&2 || true
    exit 1
  fi
  if { [ -S "$SOCKET_PATH" ] || [ -e "$SOCKET_PATH" ] || ! have_command cardano-cli; } && tip="$(query_tip_with_timeout 2>/dev/null)"; then
    slot="$(printf '%s' "$tip" | extract_json_number slot | tail -1)"
    if [ -n "$slot" ] && [ -n "$previous_slot" ] && [ "$slot" -gt "$previous_slot" ]; then
      echo "$tip"
      exit 0
    fi
    previous_slot="$slot"
  fi
  sleep 2
done

echo "Cardano devnet did not produce advancing blocks in time." >&2
docker_compose ps >&2 || true
docker_compose logs --no-color --tail=120 cardano-node >&2 || true
if [ -f "$RUNTIME_DIR/cardano-testnet.log" ]; then
  tail -80 "$RUNTIME_DIR/cardano-testnet.log" >&2 || true
fi
exit 1
