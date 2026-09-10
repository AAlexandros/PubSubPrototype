#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TESTBED_RUNTIME="$ROOT_DIR/.tools/phase-0.9"
COMPOSE_FILE="$TESTBED_RUNTIME/compose.yaml"
VERSIONS_FILE="$ROOT_DIR/ops/infra/devnet/versions.env"
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/ops/infra/devnet/runtime/registry"
export REPLICATION_REGISTRY_STATE="$ROOT_DIR/ops/infra/devnet/runtime/replication-registry/servers.json"

if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi

compose() {
  test -f "$COMPOSE_FILE" || { echo "Testbed is not bootstrapped; run scripts/testbed/up.sh" >&2; return 1; }
  (cd "$ROOT_DIR" && docker compose --env-file ops/infra/devnet/versions.env -f .tools/phase-0.9/compose.yaml "$@")
}

run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null || [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
    (cd "$ROOT_DIR" && cmd.exe /c gradlew.bat -g .gradle-user-home "$@")
  else
    "$ROOT_DIR/gradlew" --gradle-user-home "$ROOT_DIR/.gradle-user-home" "$@"
  fi
}

host_path() {
  if grep -qi microsoft /proc/version 2>/dev/null && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  elif command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}

stop_conflicting_stacks() {
  local phase file
  for phase in 0.1 0.4 0.5 0.6 0.7 0.8; do
    file="ops/infra/phase-$phase/compose.yaml"
    if [[ -f "$ROOT_DIR/$file" ]]; then
      (cd "$ROOT_DIR" && docker compose --env-file ops/infra/devnet/versions.env -f "$file" down --remove-orphans) >/dev/null 2>&1 || true
    fi
  done
}

wait_http() {
  local url="$1" label="$2"
  for _ in $(seq 1 180); do
    curl --fail --silent "$url" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "Timed out waiting for $label at $url" >&2
  return 1
}
