#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

"$DEVNET_DIR/scripts/stop.sh" || true
docker_compose down -v --remove-orphans || true
rm -rf "$RUNTIME_DIR" "$KEYS_DIR" "$STATE_DIR"
"$DEVNET_DIR/scripts/init.sh"
