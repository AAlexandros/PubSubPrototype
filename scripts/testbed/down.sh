#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
if [[ -f "$COMPOSE_FILE" ]]; then compose down --remove-orphans; fi
ops/infra/devnet/scripts/stop.sh
echo "Testbed stopped; persistent volumes and local logs were retained"
