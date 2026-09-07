#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

if [ ! -f "$RUNTIME_DIR/network.env" ]; then
  "$DEVNET_DIR/scripts/init.sh"
fi

if have_command cardano-testnet && have_command cardano-node && have_command cardano-cli; then
  mkdir -p "$RUNTIME_DIR"
  if [ -f "$RUNTIME_DIR/cardano-testnet.pid" ] && kill -0 "$(cat "$RUNTIME_DIR/cardano-testnet.pid")" 2>/dev/null; then
    echo "cardano-testnet already running with pid $(cat "$RUNTIME_DIR/cardano-testnet.pid")"
    exit 0
  fi
  export CARDANO_CLI="${CARDANO_CLI:-$(command -v cardano-cli)}"
  export CARDANO_NODE="${CARDANO_NODE:-$(command -v cardano-node)}"
  nohup cardano-testnet cardano \
    --testnet-magic "$NETWORK_MAGIC" \
    --num-pool-nodes "${CARDANO_TESTNET_NUM_POOL_NODES:-1}" \
    --slot-length "${CARDANO_TESTNET_SLOT_LENGTH:-2}" \
    --epoch-length "${CARDANO_TESTNET_EPOCH_LENGTH:-500}" \
    --output-dir "$TESTNET_DIR" \
    > "$RUNTIME_DIR/cardano-testnet.log" 2>&1 &
  echo "$!" > "$RUNTIME_DIR/cardano-testnet.pid"
else
  docker_compose up -d --build
fi

write_network_env
