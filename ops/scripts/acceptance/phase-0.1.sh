#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT_DIR"

if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

set -a
# shellcheck disable=SC1091
. ops/infra/devnet/versions.env
set +a

SOAK_SECONDS="${PHASE_0_1_SOAK_SECONDS:-600}"
COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.1/compose.yaml)
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.1"
rm -rf "$EVIDENCE_DIR"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1

capture_cardano_versions() {
  {
    echo "Official Cardano image:"
    docker run --rm --entrypoint sh "${CARDANO_NODE_IMAGE:-ghcr.io/intersectmbo/cardano-node:11.0.1}" -lc \
      'cardano-node --version; cardano-cli --version; command -v cardano-testnet >/dev/null 2>&1 && cardano-testnet version || echo "cardano-testnet not present in official image"' \
      || true
    echo
    echo "Devnet runtime:"
    if command -v cardano-node >/dev/null 2>&1; then cardano-node --version; fi
    if command -v cardano-cli >/dev/null 2>&1; then cardano-cli --version; fi
    if command -v cardano-testnet >/dev/null 2>&1; then cardano-testnet version; fi
    if docker image inspect "${CARDANO_TESTNET_IMAGE:-pubsub-cardano-testnet:11.0.1}" >/dev/null 2>&1; then
      docker run --rm --entrypoint sh "${CARDANO_TESTNET_IMAGE:-pubsub-cardano-testnet:11.0.1}" -lc \
        'cardano-node --version; cardano-cli --version; cardano-testnet version' \
        || true
    fi
  } > "$EVIDENCE_DIR/cardano-tool-versions.txt"
}

wait_for_log() {
  local service="$1"
  local pattern="$2"
  local attempts="${3:-120}"
  for _ in $(seq 1 "$attempts"); do
    if "${COMPOSE[@]}" logs "$service" 2>/dev/null | grep -q "$pattern"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $pattern in $service logs" >&2
  "${COMPOSE[@]}" logs "$service" >&2 || true
  return 1
}

unique_peer_count() {
  local service="$1"
  "${COMPOSE[@]}" logs "$service" \
    | sed -n 's/.*PEER_CONNECTED.*peer=\([0-9a-f]\{64\}\).*/\1/p' \
    | sort -u \
    | wc -l \
    | tr -d ' '
}

assert_full_mesh() {
  for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
    count="$(unique_peer_count "$service")"
    if [ "$count" -lt 2 ]; then
      echo "$service saw $count unique peers, expected 2" >&2
      "${COMPOSE[@]}" logs "$service" >&2 || true
      return 1
    fi
  done
}

./gradlew clean build :apps:pubsub-node:installDist
capture_cardano_versions

ops/infra/devnet/scripts/reset.sh
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh | tee "$EVIDENCE_DIR/cardano-chain-tip.json"
ops/infra/devnet/scripts/status.sh | tee "$EVIDENCE_DIR/cardano-status.json"
ops/infra/devnet/scripts/fund-identities.sh | tee "$EVIDENCE_DIR/funded-address-balances.txt"

"${COMPOSE[@]}" up -d --build --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3

for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  wait_for_log "$service" "NODE_STARTED"
  wait_for_log "$service" "PEER_CONNECTED"
  wait_for_log "$service" "PONG_RECEIVED"
  wait_for_log "$service" "rttMs="
done

assert_full_mesh
for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  "${COMPOSE[@]}" logs "$service" > "$EVIDENCE_DIR/$service-connections-and-ping.log"
done

echo "Running phase 0.1 soak for $SOAK_SECONDS seconds"
sleep "$SOAK_SECONDS"

if "${COMPOSE[@]}" logs pubsub-node-1 pubsub-node-2 pubsub-node-3 | grep -Ei "exception|error" | grep -Ev "protocol_error|Malformed protocol message"; then
  echo "Unexpected exception/error found during soak" >&2
  exit 1
fi

before="$("${COMPOSE[@]}" logs pubsub-node-1 | sed -n 's/.*NODE_STARTED.*nodeId=\([0-9a-f]\{64\}\).*/\1/p' | tail -1)"
"${COMPOSE[@]}" stop pubsub-node-1
wait_for_log pubsub-node-2 "PEER_DISCONNECTED"
wait_for_log pubsub-node-3 "PEER_DISCONNECTED"
"${COMPOSE[@]}" logs pubsub-node-2 pubsub-node-3 > "$EVIDENCE_DIR/node-1-stop-detection.log"
"${COMPOSE[@]}" start pubsub-node-1
sleep 15
after="$("${COMPOSE[@]}" logs pubsub-node-1 | sed -n 's/.*NODE_STARTED.*nodeId=\([0-9a-f]\{64\}\).*/\1/p' | tail -1)"
test "$before" = "$after"

wait_for_log pubsub-node-1 "PONG_RECEIVED"
assert_full_mesh
"${COMPOSE[@]}" logs pubsub-node-1 pubsub-node-2 pubsub-node-3 > "$EVIDENCE_DIR/restart-reconnection.log"

"${COMPOSE[@]}" down
ops/infra/devnet/scripts/stop.sh
ops/infra/devnet/scripts/reset.sh
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh | tee "$EVIDENCE_DIR/reset-restart-chain-tip.json"
ops/infra/devnet/scripts/stop.sh

echo "Phase 0.1 acceptance passed" | tee "$EVIDENCE_DIR/final-result.txt"
