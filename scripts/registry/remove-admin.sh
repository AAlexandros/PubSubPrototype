#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
signer="${1:?signer is required}"
topic_id="${2:?topicId is required}"
admin="${3:?administrator payment-key hash is required}"
require_payment_key_hash "$admin" "administrator"
registry_cli remove-admin "$signer" "$topic_id" "$admin"
