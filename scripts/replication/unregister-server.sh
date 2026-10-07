#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
config_file="${1:?replication-server config is required}"
test "$#" = 1 || { echo "Usage: unregister-server.sh <replication-server-config>" >&2; exit 2; }
runtime_dir="$(dirname "$REPLICATION_REGISTRY_STATE")"
replication_registry_cli unregister "$(host_path "$config_file")" "$(host_path "$runtime_dir")"
