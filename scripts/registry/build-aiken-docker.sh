#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=/workspace
. "$ROOT_DIR/scripts/registry/aiken-common.sh"

download_aiken
exec "$AIKEN_TOOLS_DIR/aiken" "$@"
