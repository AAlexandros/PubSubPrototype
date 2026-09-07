#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
vkey="${1:?verification key file is required}"
node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" server-id "$(host_path "$vkey")"
