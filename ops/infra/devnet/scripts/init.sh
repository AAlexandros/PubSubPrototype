#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

require_cardano_testnet_runtime
mkdir -p "$RUNTIME_DIR" "$KEYS_DIR" "$STATE_DIR"

for name in registry-deployer node-1 node-2 node-3; do
  mkdir -p "$KEYS_DIR/$name"
  if [ ! -f "$KEYS_DIR/$name/payment.skey" ] || [ ! -f "$KEYS_DIR/$name/payment.vkey" ]; then
    if have_command cardano-cli; then
      cardano-cli address key-gen \
        --signing-key-file "$KEYS_DIR/$name/payment.skey" \
        --verification-key-file "$KEYS_DIR/$name/payment.vkey"
    else
      cardano_cli_offline address key-gen \
        --signing-key-file "keys/$name/payment.skey" \
        --verification-key-file "keys/$name/payment.vkey"
    fi
  fi

  if [ ! -f "$KEYS_DIR/$name/payment.addr" ]; then
    build_payment_address "$KEYS_DIR/$name/payment.vkey" "$KEYS_DIR/$name/payment.addr"
  fi
done

write_network_env
echo "Initialized Cardano devnet identities and $NETWORK_ENV"
