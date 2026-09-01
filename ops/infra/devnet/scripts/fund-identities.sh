#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

MIN_LOVELACE="${DEVNET_IDENTITY_MIN_LOVELACE:-10000000}"
COLLATERAL_LOVELACE="${DEVNET_IDENTITY_COLLATERAL_LOVELACE:-5000000}"
FUNDING_DIR="$RUNTIME_DIR/funding"
mkdir -p "$FUNDING_DIR"

for name in registry-deployer node-1 node-2 node-3; do
  test -f "$KEYS_DIR/$name/payment.skey"
  test -f "$KEYS_DIR/$name/payment.vkey"
  test -f "$KEYS_DIR/$name/payment.addr"
done

all_funded() {
  local missing=0
  for name in registry-deployer node-1 node-2 node-3; do
    balance="$(identity_balance_lovelace "$name")"
    utxo_count="$(identity_utxo_count "$name")"
    echo "$name balance=$balance address=$(cat "$KEYS_DIR/$name/payment.addr")"
    if [ "$balance" -lt $((MIN_LOVELACE + COLLATERAL_LOVELACE)) ] || [ "$utxo_count" -lt 2 ]; then
      missing=1
    fi
  done
  [ "$missing" -eq 0 ]
}

if all_funded; then
  write_network_env
  exit 0
fi

if [ "${DEVNET_FUNDING_SUBMIT_DELAY_SECONDS:-60}" -gt 0 ]; then
  echo "Waiting ${DEVNET_FUNDING_SUBMIT_DELAY_SECONDS:-60}s for Cardano tx submission to become ready."
  sleep "${DEVNET_FUNDING_SUBMIT_DELAY_SECONDS:-60}"
fi

candidate_files() {
  if [ -n "${DEVNET_FUNDING_SKEY:-}" ] && [ -n "${DEVNET_FUNDING_VKEY:-}" ]; then
    printf '%s\t%s\t%s\n' "$DEVNET_FUNDING_SKEY" "$DEVNET_FUNDING_VKEY" "${DEVNET_FUNDING_ADDRESS:-}"
  fi

  for skey in "$TESTNET_DIR"/utxo-keys/*/utxo.skey; do
    [ -f "$skey" ] || continue
    vkey="${skey%.*}.vkey"
    addr_file="${skey%.*}.addr"
    [ -f "$vkey" ] && [ -f "$addr_file" ] || continue
    printf '%s\t%s\t%s\n' "$skey" "$vkey" "$(cat "$addr_file")"
  done

  find "$TESTNET_DIR" "$KEYS_DIR" -type f \( -name '*.skey' -o -name '*.sk' \) 2>/dev/null \
    | grep -Ei '(utxo|payment|wallet|user|delegate)' \
    | while read -r skey; do
        vkey="${skey%.*}.vkey"
        [ -f "$vkey" ] || vkey="${skey%.*}.vk"
        [ -f "$vkey" ] || continue
        addr_file="${skey%.*}.addr"
        if [ -f "$addr_file" ]; then
          printf '%s\t%s\t%s\n' "$skey" "$vkey" "$(cat "$addr_file")"
        else
          printf '%s\t%s\t\n' "$skey" "$vkey"
        fi
      done
}

candidate_address() {
  local skey="$1"
  local vkey="$2"
  local explicit_addr="$3"
  local id
  id="$(printf '%s' "$skey" | tr -c 'A-Za-z0-9' '_')"
  local addr_file="$FUNDING_DIR/$id.addr"

  if [ -n "$explicit_addr" ]; then
    printf '%s\n' "$explicit_addr"
    return 0
  fi

  if printf '%s' "$vkey" | grep -qi 'utxo'; then
    if build_genesis_initial_address "$vkey" > "$addr_file" 2>/dev/null; then
      cat "$addr_file"
      return 0
    fi
  fi

  if build_payment_address "$vkey" "$addr_file" 2>/dev/null; then
    cat "$addr_file"
    return 0
  fi

  return 1
}

select_funding_source() {
  local needed=$(((MIN_LOVELACE + COLLATERAL_LOVELACE) * 4 + 5000000))
  while IFS=$'\t' read -r skey vkey explicit_addr; do
    [ -n "$skey" ] && [ -f "$skey" ] && [ -n "$vkey" ] && [ -f "$vkey" ] || continue
    if addr="$(candidate_address "$skey" "$vkey" "$explicit_addr")"; then
      balance="$(address_balance_lovelace "$addr")"
      echo "funding-candidate balance=$balance address=$addr skey=$skey" >&2
      if [ "$balance" -ge "$needed" ]; then
        printf '%s\t%s\t%s\n' "$skey" "$vkey" "$addr"
        return 0
      fi
    fi
  done < <(candidate_files)
  return 1
}

if ! source_line="$(select_funding_source)"; then
  cat >&2 <<MSG
Unable to locate a funded source UTxO with enough lovelace to fund the phase identities.

Checked explicit DEVNET_FUNDING_SKEY/DEVNET_FUNDING_VKEY and generated cardano-testnet key directories under:
$TESTNET_DIR
$KEYS_DIR
MSG
  exit 1
fi

IFS=$'\t' read -r funding_skey _funding_vkey funding_addr <<EOF
$source_line
EOF

mapfile -t txins < <(address_utxos "$funding_addr")
if [ "${#txins[@]}" -eq 0 ]; then
  echo "Selected funding address has no spendable UTxOs: $funding_addr" >&2
  exit 1
fi

tx_body="$FUNDING_DIR/fund-identities.txbody"
tx_signed="$FUNDING_DIR/fund-identities.tx"
fee="${DEVNET_FUNDING_FEE_LOVELACE:-200000}"
target_total=$(((MIN_LOVELACE + COLLATERAL_LOVELACE) * 4))
source_balance="$(address_balance_lovelace "$funding_addr")"
change=$((source_balance - target_total - fee))
if [ "$change" -le 0 ]; then
  echo "Selected funding address cannot cover target outputs plus fee." >&2
  exit 1
fi

args=(latest transaction build-raw --fee "$fee" --out-file "$tx_body")
for txin in "${txins[@]}"; do
  args+=(--tx-in "$txin")
done
for name in registry-deployer node-1 node-2 node-3; do
  args+=(--tx-out "$(cat "$KEYS_DIR/$name/payment.addr")+$MIN_LOVELACE")
  args+=(--tx-out "$(cat "$KEYS_DIR/$name/payment.addr")+$COLLATERAL_LOVELACE")
done
args+=(--tx-out "$funding_addr+$change")

cardano_cli "${args[@]}"
cardano_cli latest transaction sign \
  --tx-body-file "$tx_body" \
  --signing-key-file "$funding_skey" \
  --testnet-magic "$NETWORK_MAGIC" \
  --out-file "$tx_signed"
cardano_cli latest transaction submit --tx-file "$tx_signed" --testnet-magic "$NETWORK_MAGIC"

for _ in $(seq 1 60); do
  if all_funded; then
    write_network_env
    exit 0
  fi
  sleep 2
done

echo "Funding transaction submitted but target balances were not confirmed in time." >&2
exit 1
