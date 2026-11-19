#!/usr/bin/env bash
set -euo pipefail

NETWORK_MAGIC="${CARDANO_NETWORK_MAGIC:-42}"
NUM_POOL_NODES="${CARDANO_TESTNET_NUM_POOL_NODES:-3}"
SLOT_LENGTH="${CARDANO_TESTNET_SLOT_LENGTH:-2}"
EPOCH_LENGTH="${CARDANO_TESTNET_EPOCH_LENGTH:-10000}"
ACTIVE_SLOTS_COEFF="${CARDANO_TESTNET_ACTIVE_SLOTS_COEFF:-1.0}"
OUTPUT_DIR="${CARDANO_TESTNET_OUTPUT_DIR:-/devnet/runtime/testnet}"

if { [ -e "$OUTPUT_DIR" ] && [ ! -d "$OUTPUT_DIR" ]; } || { [ -L "$OUTPUT_DIR" ] && [ ! -e "$OUTPUT_DIR" ]; }; then
  echo "Removing stale non-directory Cardano testnet output path: $OUTPUT_DIR" >&2
  rm -f -- "$OUTPUT_DIR"
fi

if [ -d "$OUTPUT_DIR" ]; then
  if rmdir "$OUTPUT_DIR" 2>/dev/null; then
    echo "Removed empty Cardano testnet output directory: $OUTPUT_DIR" >&2
  else
    echo "Cardano testnet output path already contains data before startup: $OUTPUT_DIR" >&2
    ls -la "$OUTPUT_DIR" >&2
  fi
fi

mkdir -p /devnet/state

export CARDANO_CLI="${CARDANO_CLI:-$(command -v cardano-cli)}"
export CARDANO_NODE="${CARDANO_NODE:-$(command -v cardano-node)}"

exec cardano-testnet cardano \
  --testnet-magic "$NETWORK_MAGIC" \
  --num-pool-nodes "$NUM_POOL_NODES" \
  --slot-length "$SLOT_LENGTH" \
  --epoch-length "$EPOCH_LENGTH" \
  --active-slots-coeff "$ACTIVE_SLOTS_COEFF" \
  --output-dir "$OUTPUT_DIR"
