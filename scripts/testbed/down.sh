#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
if [[ -f "$COMPOSE_FILE" ]]; then compose down --remove-orphans; fi
ops/infra/devnet/scripts/stop.sh
echo "Phase 0.9 testbed stopped; persistent volumes and results were retained"
