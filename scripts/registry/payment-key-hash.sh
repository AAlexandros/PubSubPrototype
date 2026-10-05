#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT_DIR/ops/infra/devnet/scripts/common.sh"

identity="${1:?local devnet identity is required}"
verification_key="$KEYS_DIR/$identity/payment.vkey"
if [ ! -f "$verification_key" ]; then
  echo "Payment verification key is missing for local devnet identity $identity: $verification_key" >&2
  exit 1
fi

# Acceptance-only bridge: production callers receive this public hash from the identity owner.
cardano_cli_offline address key-hash --payment-verification-key-file "$verification_key"
