#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

if [ -f "$RUNTIME_DIR/cardano-testnet.pid" ]; then
  pid="$(cat "$RUNTIME_DIR/cardano-testnet.pid")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
  fi
  rm -f "$RUNTIME_DIR/cardano-testnet.pid"
fi

docker_compose down --remove-orphans
