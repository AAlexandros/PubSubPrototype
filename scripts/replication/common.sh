#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT_DIR/scripts/lib/platform.sh"
REPLICATION_REGISTRY_STATE="${REPLICATION_REGISTRY_STATE:-$ROOT_DIR/ops/infra/devnet/runtime/replication-registry/servers.json}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
host_path() {
  pubsub_host_path "$1"
}
node_file() {
  local script="$1"
  shift
  pubsub_run_node_file "$script" "$@"
}
replication_registry_cli() {
  mkdir -p "$(dirname "$REPLICATION_REGISTRY_STATE")"
  local state_arg
  state_arg="$(host_path "$REPLICATION_REGISTRY_STATE")"
  pubsub_run_gradle "$ROOT_DIR" -q :libs:persistence-core:run --args="$state_arg $*"
}
