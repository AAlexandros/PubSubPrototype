#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT_DIR/scripts/registry/aiken-common.sh"
download_aiken
cd "$ROOT_DIR/contracts/replication-registry"
"$AIKEN_TOOLS_DIR/aiken" "$@"
