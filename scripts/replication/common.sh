#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPLICATION_REGISTRY_STATE="${REPLICATION_REGISTRY_STATE:-$ROOT_DIR/ops/infra/devnet/runtime/replication-registry/servers.json}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
host_path() {
  if grep -qi microsoft /proc/version 2>/dev/null && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  elif command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}
node_file() {
  local script
  script="$(host_path "$1")"
  shift
  node "$script" "$@"
}
replication_registry_cli() {
  mkdir -p "$(dirname "$REPLICATION_REGISTRY_STATE")"
  local state_arg
  state_arg="$(host_path "$REPLICATION_REGISTRY_STATE")"
  if grep -qi microsoft /proc/version 2>/dev/null; then
    cmd.exe /c gradlew.bat -q -g .gradle-user-home :libs:persistence-core:run --args="$state_arg $*"
  else
    "$ROOT_DIR/gradlew" -q --gradle-user-home "$GRADLE_USER_HOME" :libs:persistence-core:run --args="$state_arg $*"
  fi
}
