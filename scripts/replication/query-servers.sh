#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
test "$#" -le 1 && { test "$#" -eq 0 || test "$1" = "--all"; } \
  || { echo "Usage: query-servers.sh [--all]" >&2; exit 2; }
runtime_dir="$REPLICATION_REGISTRY_RUNTIME_DIR"
backend="${CARDANO_CLI_BACKEND:-DEVNET}"
all_arg=()
if [ "${1:-}" = "--all" ]; then all_arg=(--all); fi
replication_registry_cli query "$(host_path "$runtime_dir")" "$backend" "${all_arg[@]}"
