#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT_DIR/scripts/lib/platform.sh"
REGISTRY_RUNTIME_DIR="${REGISTRY_RUNTIME_DIR:-$ROOT_DIR/ops/infra/devnet/runtime/registry}"
CARDANO_CLI_BACKEND="${CARDANO_CLI_BACKEND:-DEVNET}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"

host_path() {
  pubsub_host_path "$1"
}

run_gradle() {
  pubsub_run_gradle "$ROOT_DIR" "$@"
}

run_node() {
  local script="$1"
  local arg="$2"
  pubsub_run_node_file "$script" "$(pubsub_node_path "$arg")"
}

registry_cli() {
  mkdir -p "$REGISTRY_RUNTIME_DIR"
  runtime_arg="$(host_path "$REGISTRY_RUNTIME_DIR")"
  if command -v timeout >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && export ROOT_DIR && export -f pubsub_is_wsl pubsub_is_msys pubsub_uses_windows_tools pubsub_run_gradle run_gradle && timeout "${REGISTRY_CLI_TIMEOUT_SECONDS:-600}s" bash -c 'run_gradle "$@"' _ -q :libs:runRegistryCli --args="$runtime_arg $CARDANO_CLI_BACKEND $*")
  else
    (cd "$ROOT_DIR" && run_gradle -q :libs:runRegistryCli --args="$runtime_arg $CARDANO_CLI_BACKEND $*")
  fi
}

require_payment_key_hash() {
  local value="${1:-}"
  local description="${2:-owner or administrator}"
  if [[ ! "$value" =~ ^[0-9a-fA-F]{56}$ ]]; then
    echo "$description must be a 56-character Cardano payment-key hash: $value" >&2
    return 1
  fi
}

require_payment_key_hashes() {
  local values="${1:-}"
  local value
  local hashes=()
  if [ "$values" = "-" ]; then
    return 0
  fi
  IFS=',' read -r -a hashes <<< "$values"
  for value in "${hashes[@]}"; do
    require_payment_key_hash "$value" "administrator"
  done
}
