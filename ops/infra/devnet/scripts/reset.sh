#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

"$DEVNET_DIR/scripts/stop.sh" || true
docker_compose down -v --remove-orphans || true

remove_devnet_path() {
  local target="$1"
  if grep -qi microsoft /proc/version 2>/dev/null && command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command "Remove-Item -LiteralPath '$(docker_host_path "$target")' -Recurse -Force -ErrorAction SilentlyContinue"
  else
    rm -rf "$target"
  fi
}

remove_devnet_path "$RUNTIME_DIR"
remove_devnet_path "$KEYS_DIR"
remove_devnet_path "$STATE_DIR"
"$DEVNET_DIR/scripts/init.sh"
