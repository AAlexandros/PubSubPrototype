#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
vkey="${1:?verification key file is required}"
if [[ ! -f "$vkey" && -f "$ROOT_DIR/$vkey" ]]; then
  vkey="$ROOT_DIR/$vkey"
fi
if [[ ! -f "$vkey" ]]; then
  printf 'Verification key not found: %s\n' "$vkey" >&2
  exit 1
fi
vkey="$(cd "$(dirname "$vkey")" && pwd -P)/$(basename "$vkey")"
replication_registry_cli server-id "$(host_path "$vkey")"
