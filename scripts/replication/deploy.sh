#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
. "$ROOT_DIR/ops/infra/devnet/scripts/common.sh"
source_script="$ROOT_DIR/contracts/replication-registry/build/replication-registry.plutus.json"
test -f "$source_script" || "$ROOT_DIR/scripts/replication/build.sh"
runtime_dir="$(dirname "$REPLICATION_REGISTRY_STATE")"
mkdir -p "$runtime_dir"
script="$runtime_dir/replication-registry.plutus.json"
cp "$source_script" "$script"
address="$(cardano_cli address build --payment-script-file "$script" --testnet-magic "$NETWORK_MAGIC")"
{
  echo "REPLICATION_REGISTRY_VALIDATOR_ADDRESS=$address"
  echo "REPLICATION_REGISTRY_SCRIPT=$script"
  echo "REPLICATION_REGISTRY_STATE=$REPLICATION_REGISTRY_STATE"
} > "$runtime_dir/deployment.env"
printf 'REPLICATION_REGISTRY_DEPLOYED validatorAddress=%s\n' "$address"
