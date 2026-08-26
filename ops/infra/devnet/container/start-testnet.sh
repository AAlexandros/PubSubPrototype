#!/usr/bin/env bash
set -euo pipefail

NETWORK_MAGIC="${CARDANO_NETWORK_MAGIC:-42}"
NUM_POOL_NODES="${CARDANO_TESTNET_NUM_POOL_NODES:-3}"
SLOT_LENGTH="${CARDANO_TESTNET_SLOT_LENGTH:-0.2}"
EPOCH_LENGTH="${CARDANO_TESTNET_EPOCH_LENGTH:-500}"
ACTIVE_SLOTS_COEFF="${CARDANO_TESTNET_ACTIVE_SLOTS_COEFF:-1.0}"
OUTPUT_DIR="${CARDANO_TESTNET_OUTPUT_DIR:-/devnet/runtime/testnet}"

mkdir -p "$OUTPUT_DIR" /devnet/state

export CARDANO_CLI="${CARDANO_CLI:-$(command -v cardano-cli)}"
export CARDANO_NODE="${CARDANO_NODE:-$(command -v cardano-node)}"

exec cardano-testnet cardano \
  --testnet-magic "$NETWORK_MAGIC" \
  --num-pool-nodes "$NUM_POOL_NODES" \
  --slot-length "$SLOT_LENGTH" \
  --epoch-length "$EPOCH_LENGTH" \
  --active-slots-coeff "$ACTIVE_SLOTS_COEFF" \
  --output-dir "$OUTPUT_DIR"
