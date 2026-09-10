#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ $# -ne 1 ]]; then echo "Usage: scripts/experiments/run.sh <scenario.yaml>" >&2; exit 2; fi
scenario="$1"
[[ -f "$scenario" ]] || scenario="$ROOT_DIR/$scenario"
[[ -f "$scenario" ]] || { echo "Scenario not found: $1" >&2; exit 2; }
node_count="$(node "$ROOT_DIR/scripts/experiments/run.mjs" inspect "$scenario")"
echo "[phase-0.9] Starting $node_count-node testbed..." >&2
"$ROOT_DIR/scripts/testbed/up.sh" --nodes "$node_count" >&2
run_dir="$(node "$ROOT_DIR/scripts/experiments/run.mjs" run "$scenario" "$ROOT_DIR")"
echo "[phase-0.9] Normalizing telemetry to Parquet and summaries..." >&2
"$ROOT_DIR/scripts/experiments/telemetry.sh" normalize "$run_dir" >&2
echo "[phase-0.9] Run complete: $run_dir" >&2
echo "$run_dir"
