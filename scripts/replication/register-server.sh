#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
server_id="${1:?serverId is required}"
operator="${2:?operator identity is required}"
host="${3:?host is required}"
port="${4:?port is required}"
start_epoch="${5:?commitment start epoch is required}"
end_epoch="${6:?commitment end epoch is required}"
. "$ROOT_DIR/ops/infra/devnet/scripts/common.sh"
runtime_dir="$(dirname "$REPLICATION_REGISTRY_STATE")"
. "$runtime_dir/deployment.env"
vkey="$KEYS_DIR/$operator/payment.vkey"
skey="$KEYS_DIR/$operator/payment.skey"
address="$(cat "$KEYS_DIR/$operator/payment.addr")"
derived="$(node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" server-id "$(host_path "$vkey")")"
test "$derived" = "$server_id" || { echo "serverId does not match encoded Cardano verification key" >&2; exit 1; }
operator_hash="$(cardano_cli address key-hash --payment-verification-key-file "$vkey")"
artifact="$runtime_dir/tx/$server_id"
mkdir -p "$(dirname "$artifact")"
node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" datum "$(host_path "$artifact.datum.json")" \
  "$server_id" "$operator_hash" "$host" "$port" "$start_epoch" "$end_epoch" true
"$ROOT_DIR/scripts/replication/query-servers.sh" >/dev/null
existing="$(node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" find "$(host_path "$runtime_dir/script-utxos.json")" "$server_id" 2>/dev/null || true)"
if [ -n "$existing" ]; then
  IFS=$'\t' read -r script_utxo registered_operator _ _ _ _ active <<< "$existing"
  test "$registered_operator" = "$operator_hash" || { echo "controlling Cardano identity signature is required" >&2; exit 1; }
  test "$active" = true || { echo "an inactive permanent serverId cannot be re-registered" >&2; exit 1; }
  mapfile -t payments < <(address_utxos "$address")
  test "${#payments[@]}" -ge 2
  node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" redeemer "$(host_path "$artifact.redeemer.json")" update \
    "$host" "$port" "$start_epoch" "$end_epoch"
  cardano_cli latest transaction build --testnet-magic "$NETWORK_MAGIC" --change-address "$address" \
    --tx-in "$script_utxo" --tx-in-script-file "$REPLICATION_REGISTRY_SCRIPT" --tx-in-inline-datum-present \
    --tx-in-redeemer-file "$artifact.redeemer.json" --tx-in "${payments[0]}" --tx-in-collateral "${payments[1]}" \
    --tx-out "$REPLICATION_REGISTRY_VALIDATOR_ADDRESS+2000000" --tx-out-inline-datum-file "$artifact.datum.json" \
    --required-signer-hash "$operator_hash" --out-file "$artifact.txbody"
else
  txin="$(address_utxos "$address" | head -1)"
  test -n "$txin"
  cardano_cli latest transaction build --testnet-magic "$NETWORK_MAGIC" --change-address "$address" \
    --tx-in "$txin" --tx-out "$REPLICATION_REGISTRY_VALIDATOR_ADDRESS+2000000" \
    --tx-out-inline-datum-file "$artifact.datum.json" --required-signer-hash "$operator_hash" --out-file "$artifact.txbody"
fi
cardano_cli latest transaction sign --tx-body-file "$artifact.txbody" --signing-key-file "$skey" \
  --testnet-magic "$NETWORK_MAGIC" --out-file "$artifact.tx"
cardano_cli latest transaction submit --tx-file "$artifact.tx" --testnet-magic "$NETWORK_MAGIC"
for _ in $(seq 1 60); do
  if "$ROOT_DIR/scripts/replication/query-servers.sh" | grep -q "$server_id"; then exit 0; fi
  sleep 2
done
echo "registration transaction did not become visible: $server_id" >&2
exit 1
