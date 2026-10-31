#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

if [[ $# -gt 1 || ( $# -eq 1 && "${1:-}" != --no-init ) ]]; then
  echo "Usage: $0 [--no-init]" >&2
  exit 2
fi

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
if [[ "${1:-}" != --no-init ]]; then
  "$DEVNET_DIR/scripts/init.sh"
fi
