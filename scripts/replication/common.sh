#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT_DIR/scripts/lib/platform.sh"
REPLICATION_REGISTRY_RUNTIME_DIR="${REPLICATION_REGISTRY_RUNTIME_DIR:-$ROOT_DIR/ops/infra/devnet/runtime/replication-registry}"
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
  mkdir -p "$REPLICATION_REGISTRY_RUNTIME_DIR"
  pubsub_run_gradle "$ROOT_DIR" -q :apps:replication-server:runReplicationRegistryCli --args="$*"
}
