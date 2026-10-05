#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
signer="${1:?signer is required}"
topic_id="${2:?topicId is required}"
owner="${3:?owner payment-key hash is required}"
require_payment_key_hash "$owner" "owner"
registry_cli add-owner "$signer" "$topic_id" "$owner"
