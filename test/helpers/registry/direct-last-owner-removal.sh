#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
. "$ROOT_DIR/scripts/registry/common.sh"

signer="${1:?signer is required}"
topic_id="${2:?topicId is required}"
owner="${3:?owner payment-key hash is required}"
require_payment_key_hash "$owner" "owner"
registry_cli direct-last-owner-removal "$signer" "$topic_id" "$owner"
