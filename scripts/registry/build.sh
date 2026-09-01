#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
. "$ROOT_DIR/scripts/registry/aiken-common.sh"

EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.2"
mkdir -p "$EVIDENCE_DIR"

run_aiken_wsl() {
  command -v wsl.exe >/dev/null 2>&1 || return 1
  command -v cygpath >/dev/null 2>&1 || return 1

  local win_root wsl_root quoted_args arg
  if [[ "$ROOT_DIR" == /[a-zA-Z]/* ]]; then
    wsl_root="/mnt/${ROOT_DIR:1:1}/${ROOT_DIR:3}"
  else
    win_root="$(cygpath -w "$ROOT_DIR")"
    wsl_root="$(cmd.exe /c wsl.exe wslpath -u "$win_root" | tr -d '\r')"
  fi
  quoted_args=()
  for arg in "$@"; do
    quoted_args+=("$(printf '%q' "$arg")")
  done

  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command "wsl.exe bash -lc 'cd $(printf '%q' "$wsl_root") && bash ./scripts/registry/build-aiken-wsl.sh ${quoted_args[*]}'"
  else
    wsl.exe bash -lc "cd $(printf '%q' "$wsl_root") && bash ./scripts/registry/build-aiken-wsl.sh ${quoted_args[*]}"
  fi
}

run_aiken() {
  if [ -n "${AIKEN_CMD:-}" ]; then
    # shellcheck disable=SC2086
    $AIKEN_CMD "$@"
  elif command -v aiken >/dev/null 2>&1; then
    aiken "$@"
  elif [[ "$(uname -s | tr '[:upper:]' '[:lower:]')" == mingw* ]]; then
    run_aiken_wsl "$@"
  elif download_aiken; then
    local quoted_args=()
    local arg
    for arg in "$@"; do
      quoted_args+=("$(printf '%q' "$arg")")
    done
    bash -lc "$(printf '%q' "$AIKEN_TOOLS_DIR/aiken") ${quoted_args[*]}"
  elif command -v npx >/dev/null 2>&1; then
    npx --yes @aiken-lang/aiken@1.1.23 "$@"
  elif command -v cmd.exe >/dev/null 2>&1; then
    cmd.exe /c npx --yes @aiken-lang/aiken@1.1.23 "$@"
  else
    echo "aiken not found and npx fallback is unavailable" >&2
    return 1
  fi
}

# Build the Aiken contract, export cardano-cli script files, then run registry tests.
echo "Topic Registry contract build"
echo "Aiken source: contracts/topic-registry"
(cd "$ROOT_DIR/contracts/topic-registry" && run_aiken check --skip-tests && run_aiken build)
run_node "$ROOT_DIR/scripts/registry/export-aiken-scripts.mjs" "$ROOT_DIR"
(cd "$ROOT_DIR" && run_gradle -q :libs:registry-api:test :libs:registry-cardano:test)
