#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.9"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
cd "$ROOT_DIR"

resume_run="${PHASE_0_9_RESUME_RUN:-}"

finish() {
  local result=$?
  if [[ -f "$ROOT_DIR/.tools/phase-0.9/compose.yaml" ]]; then
    docker compose --env-file ops/infra/devnet/versions.env -f .tools/phase-0.9/compose.yaml ps > "$EVIDENCE_DIR/final-process-status.txt" 2>&1 || true
    docker compose --env-file ops/infra/devnet/versions.env -f .tools/phase-0.9/compose.yaml logs --no-color > "$EVIDENCE_DIR/final-integrated.log" 2>&1 || true
  fi
  if (( result != 0 )); then echo "Phase 0.9 acceptance FAILED ($result)" > "$EVIDENCE_DIR/final-result.txt"; fi
}
trap finish EXIT

if [[ -n "$resume_run" ]]; then
  run_dir="$resume_run"
  [[ -d "$run_dir/raw" ]] || { echo "Resume run does not contain raw telemetry: $run_dir" >&2; exit 2; }
  echo "[phase-0.9] Resuming acceptance from completed run: $run_dir"
  parquet_count="$(find "$run_dir/parquet" -maxdepth 1 -name '*.parquet' 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "$parquet_count" == "11" && -f "$run_dir/derived/summary.json" && -f "$run_dir/derived/summary.csv" ]]; then
    echo "[phase-0.9] Reusing the run's normalized telemetry"
  else
    scripts/experiments/telemetry.sh normalize "$run_dir"
  fi
else
  if grep -qi microsoft /proc/version 2>/dev/null; then
    cmd.exe /c gradlew.bat -g .gradle-user-home clean test --no-daemon
  else
    ./gradlew --gradle-user-home .gradle-user-home clean test --no-daemon
  fi
  node --check scripts/testbed/generate.mjs
  node --check scripts/experiments/run.mjs
  node --check scripts/acceptance/phase-0.9.mjs

  scripts/testbed/reset.sh
  run_dir="$(scripts/experiments/run.sh ops/config/phase-0.9/scenarios/acceptance.yaml)"
fi
scripts/replication/query-servers.sh > "$EVIDENCE_DIR/replication-registry.json"
scripts/registry/query.sh --all > "$EVIDENCE_DIR/topic-registry.json"
scripts/testbed/status.sh > "$EVIDENCE_DIR/testbed-status.txt"
scripts/testbed/collect-logs.sh "$EVIDENCE_DIR/bootstrap-and-runtime" >/dev/null
scripts/experiments/aggregate.sh "$ROOT_DIR/results"
node scripts/acceptance/phase-0.9.mjs "$run_dir" "$EVIDENCE_DIR" "$ROOT_DIR"

find "$run_dir/raw" -maxdepth 1 -type f -printf '%f %s bytes\n' | sort > "$EVIDENCE_DIR/raw-inventory.txt"
find "$run_dir/parquet" -maxdepth 1 -type f -printf '%f %s bytes\n' | sort > "$EVIDENCE_DIR/parquet-inventory.txt"
find docs/architecture -maxdepth 1 -type f -printf '%f\n' | sort > "$EVIDENCE_DIR/architecture-inventory.txt"
cp results/data-dictionary.md "$EVIDENCE_DIR/data-dictionary.md"
echo "Phase 0.9 acceptance passed" | tee "$EVIDENCE_DIR/final-result.txt"
