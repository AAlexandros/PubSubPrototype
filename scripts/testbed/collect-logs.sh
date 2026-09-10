#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
target="${1:-$ROOT_DIR/results/collected-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$target"
compose ps --format json > "$target/compose-processes.json"
compose logs --no-color > "$target/testbed.log"
ops/infra/devnet/scripts/status.sh > "$target/cardano-tip.json" || true
cp "$COMPOSE_FILE" "$target/compose.yaml"
echo "$target"
