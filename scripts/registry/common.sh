#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REGISTRY_RUNTIME_DIR="${REGISTRY_RUNTIME_DIR:-$ROOT_DIR/ops/infra/devnet/runtime/registry}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"

host_path() {
  if command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  elif command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}

run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then
    cmd.exe /c gradlew.bat "$@"
  else
    ./gradlew "$@"
  fi
}

run_node() {
  local script="$1"
  local arg="$2"
  if command -v cygpath >/dev/null 2>&1 && node -p "process.platform" 2>/dev/null | grep -q '^win32$'; then
    node "$(cygpath -w "$script")" "$(cygpath -w "$arg")"
  else
    node "$script" "$arg"
  fi
}

registry_cli() {
  mkdir -p "$REGISTRY_RUNTIME_DIR"
  runtime_arg="$(host_path "$REGISTRY_RUNTIME_DIR")"
  if command -v timeout >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && export -f run_gradle && timeout "${REGISTRY_CLI_TIMEOUT_SECONDS:-600}s" bash -c 'run_gradle "$@"' _ -q :libs:registry-cardano:run --args="$runtime_arg $*")
  else
    (cd "$ROOT_DIR" && run_gradle -q :libs:registry-cardano:run --args="$runtime_arg $*")
  fi
}
