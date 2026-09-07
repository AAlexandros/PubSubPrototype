#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
server_id="${1:?serverId is required}"
operator="${2:?operator identity is required}"
. "$ROOT_DIR/ops/infra/devnet/scripts/common.sh"
runtime_dir="$(dirname "$REPLICATION_REGISTRY_STATE")"
. "$runtime_dir/deployment.env"
"$ROOT_DIR/scripts/replication/query-servers.sh" >/dev/null
IFS=$'\t' read -r script_utxo registered_operator host port start_epoch end_epoch active < <(
  node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" find "$(host_path "$runtime_dir/script-utxos.json")" "$server_id")
test "$active" = true || { echo "server is already inactive" >&2; exit 1; }
vkey="$KEYS_DIR/$operator/payment.vkey"
skey="$KEYS_DIR/$operator/payment.skey"
operator_hash="$(cardano_cli address key-hash --payment-verification-key-file "$vkey")"
test "$operator_hash" = "$registered_operator" || { echo "controlling Cardano identity signature is required" >&2; exit 1; }
address="$(cat "$KEYS_DIR/$operator/payment.addr")"
mapfile -t payments < <(address_utxos "$address")
test "${#payments[@]}" -ge 2
artifact="$runtime_dir/tx/unregister-$server_id"
node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" datum "$(host_path "$artifact.datum.json")" \
  "$server_id" "$operator_hash" "$host" "$port" "$start_epoch" "$end_epoch" false
node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" redeemer "$(host_path "$artifact.redeemer.json")" unregister
cardano_cli latest transaction build --testnet-magic "$NETWORK_MAGIC" --change-address "$address" \
  --tx-in "$script_utxo" --tx-in-script-file "$REPLICATION_REGISTRY_SCRIPT" --tx-in-inline-datum-present \
  --tx-in-redeemer-file "$artifact.redeemer.json" --tx-in "${payments[0]}" --tx-in-collateral "${payments[1]}" \
  --tx-out "$REPLICATION_REGISTRY_VALIDATOR_ADDRESS+2000000" --tx-out-inline-datum-file "$artifact.datum.json" \
  --required-signer-hash "$operator_hash" --out-file "$artifact.txbody"
cardano_cli latest transaction sign --tx-body-file "$artifact.txbody" --signing-key-file "$skey" \
  --testnet-magic "$NETWORK_MAGIC" --out-file "$artifact.tx"
cardano_cli latest transaction submit --tx-file "$artifact.tx" --testnet-magic "$NETWORK_MAGIC"
for _ in $(seq 1 60); do
  "$ROOT_DIR/scripts/replication/query-servers.sh" >/dev/null
  observed="$(node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" find \
    "$(host_path "$runtime_dir/script-utxos.json")" "$server_id" 2>/dev/null || true)"
  if [ -n "$observed" ]; then
    IFS=$'\t' read -r _ _ _ _ _ _ observed_active <<< "$observed"
    if [ "$observed_active" = false ]; then exit 0; fi
  fi
  sleep 2
done
echo "unregistration transaction did not become visible: $server_id" >&2
exit 1
