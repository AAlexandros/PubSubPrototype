#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

signer="${1:?signer is required}"
name="${2:?name is required}"
admins="${3:--}"
publishers="${4:--}"
replication_factor="${5:?replication factor is required}"
retention_period="${6:?retention period is required}"
registry_cli create "$signer" "$name" "$admins" "$publishers" "$replication_factor" "$retention_period"
