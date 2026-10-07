#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
runtime_dir="$(dirname "$REPLICATION_REGISTRY_STATE")"
replication_registry_cli deploy "$(host_path "$runtime_dir")" "${CARDANO_CLI_BACKEND:-DEVNET}"
