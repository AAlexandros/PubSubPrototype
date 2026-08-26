#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

previous_slot=""
for _ in $(seq 1 "${DEVNET_HEALTH_ATTEMPTS:-120}"); do
  if { [ -S "$SOCKET_PATH" ] || [ -e "$SOCKET_PATH" ] || ! have_command cardano-cli; } && tip="$(query_tip_json 2>/dev/null)"; then
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
if [ -f "$RUNTIME_DIR/cardano-testnet.log" ]; then
  tail -80 "$RUNTIME_DIR/cardano-testnet.log" >&2 || true
fi
exit 1
