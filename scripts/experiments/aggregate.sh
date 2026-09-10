#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
results_dir="${1:-$ROOT_DIR/results}"
[[ -d "$results_dir" ]] || { echo "Results directory not found: $results_dir" >&2; exit 2; }
echo "[phase-0.9] Aggregating experiment results..." >&2
"$ROOT_DIR/scripts/experiments/telemetry.sh" aggregate "$results_dir" >&2
echo "Aggregated results are in $results_dir/combined"
