#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT_DIR/scripts/lib/platform.sh"
TESTBED_RUNTIME="$ROOT_DIR/.tools/testbed"
COMPOSE_FILE="$TESTBED_RUNTIME/compose.yaml"
VERSIONS_FILE="$ROOT_DIR/test/devnet/versions.env"
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/test/devnet/runtime/registry"
export REPLICATION_REGISTRY_RUNTIME_DIR="$ROOT_DIR/test/devnet/runtime/replication-registry"
export CARDANO_CLI_BACKEND=DEVNET

compose() {
  test -f "$COMPOSE_FILE" || { echo "Testbed is not bootstrapped; run test/testbed/up.sh" >&2; return 1; }
  (cd "$ROOT_DIR" && docker compose -p "${PUBSUB_TESTBED_PROJECT:-pubsub-testbed}" --env-file test/devnet/versions.env -f .tools/testbed/compose.yaml "$@")
}

run_gradle() {
  pubsub_run_gradle "$ROOT_DIR" "$@"
}

host_path() {
  pubsub_host_path "$1"
}

stop_conflicting_stacks() {
  local spec project fixture file
  for spec in \
      peer-connectivity:three-node \
      topic-registry-policy:three-node \
      event-authorization-delivery:three-node \
      secure-peer-sampling:secure-peer-sampling \
      topic-navigation:topic-navigation \
      topic-dissemination-repair:topic-dissemination-repair \
      subscriber-local-catch-up:subscriber-local-catch-up; do
    project="${spec%%:*}"
    fixture="${spec#*:}"
    file="test/fixtures/$fixture/compose.yaml"
    if [[ -f "$ROOT_DIR/$file" ]]; then
      (cd "$ROOT_DIR" && docker compose -p "$project" --env-file test/devnet/versions.env -f "$file" down --remove-orphans) >/dev/null 2>&1 || true
    fi
  done
}

wait_http() {
  local url="$1" label="$2"
  for attempt in $(seq 1 180); do
    curl --fail --silent --connect-timeout 1 --max-time 2 "$url" >/dev/null 2>&1 && return 0
    if (( attempt % 5 == 0 )) && [[ -z "$(compose ps --status running -q "$label")" ]]; then
      echo "$label exited before becoming ready at $url" >&2
      compose logs --no-color --tail 40 "$label" >&2 || true
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for $label at $url" >&2
  return 1
}
