#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
runtime_dir="$(dirname "$REPLICATION_REGISTRY_STATE")"
if [ -f "$runtime_dir/deployment.env" ]; then
  . "$ROOT_DIR/ops/infra/devnet/scripts/common.sh"
  . "$runtime_dir/deployment.env"
  utxos="$runtime_dir/script-utxos.json"
  cardano_cli query utxo --address "$REPLICATION_REGISTRY_VALIDATOR_ADDRESS" \
    --testnet-magic "$NETWORK_MAGIC" --output-json > "$utxos"
  node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" decode "$(host_path "$utxos")" "$(host_path "$REPLICATION_REGISTRY_STATE")"
else
  replication_registry_cli query "${1:-}"
fi
