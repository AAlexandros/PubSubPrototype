#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
test "$#" -le 1 && { test "$#" -eq 0 || test "$1" = "--all"; } \
  || { echo "Usage: query-servers.sh [--all]" >&2; exit 2; }
runtime_dir="$(dirname "$REPLICATION_REGISTRY_STATE")"
if [ -f "$runtime_dir/deployment.env" ]; then
  backend="${CARDANO_CLI_BACKEND:-DEVNET}"
else
  backend="${CARDANO_CLI_BACKEND:-CACHE_ONLY}"
fi
all_arg=()
if [ "${1:-}" = "--all" ]; then all_arg=(--all); fi
replication_registry_cli query "$(host_path "$runtime_dir")" "$backend" "${all_arg[@]}"
