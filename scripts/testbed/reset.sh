#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
if [[ -f "$COMPOSE_FILE" ]]; then compose down -v --remove-orphans; fi
stop_conflicting_stacks
ops/infra/devnet/scripts/reset.sh
resolved="$(cd "$(dirname "$TESTBED_RUNTIME")" && pwd)/$(basename "$TESTBED_RUNTIME")"
[[ "$resolved" == "$ROOT_DIR/.tools/phase-0.9" ]] || { echo "Refusing to wipe unexpected path: $resolved" >&2; exit 1; }
rm -rf -- "$resolved"
echo "Phase 0.9 runtime state and Docker volumes were wiped; results were retained"
