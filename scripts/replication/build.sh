#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT_DIR/scripts/replication/common.sh"
. "$ROOT_DIR/scripts/registry/aiken-common.sh"
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
  for arg in "$@"; do quoted_args+=("$(printf '%q' "$arg")"); done
  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command \
      "wsl.exe bash -lc 'cd $(printf '%q' "$wsl_root") && bash ./scripts/replication/build-aiken-wsl.sh ${quoted_args[*]}'"
  else
    wsl.exe bash -lc "cd $(printf '%q' "$wsl_root") && bash ./scripts/replication/build-aiken-wsl.sh ${quoted_args[*]}"
  fi
}
if command -v aiken >/dev/null 2>&1; then
  (cd "$ROOT_DIR/contracts/replication-registry" && aiken check --skip-tests && aiken build)
elif [[ "$(uname -s | tr '[:upper:]' '[:lower:]')" == mingw* ]] && command -v wsl.exe >/dev/null 2>&1; then
  run_aiken_wsl check --skip-tests
  run_aiken_wsl build
else
  download_aiken
  (cd "$ROOT_DIR/contracts/replication-registry" && "$AIKEN_TOOLS_DIR/aiken" check --skip-tests && "$AIKEN_TOOLS_DIR/aiken" build)
fi
node_file "$ROOT_DIR/scripts/replication/registry-data.mjs" export \
  "$(host_path "$ROOT_DIR/contracts/replication-registry/plutus.json")" \
  "$(host_path "$ROOT_DIR/contracts/replication-registry/build/replication-registry.plutus.json")"
if grep -qi microsoft /proc/version 2>/dev/null; then
  (cd "$ROOT_DIR" && cmd.exe /c gradlew.bat -q -g .gradle-user-home :libs:persistence-core:test)
else
  "$ROOT_DIR/gradlew" -q --gradle-user-home "$ROOT_DIR/.gradle-user-home" :libs:persistence-core:test
fi
