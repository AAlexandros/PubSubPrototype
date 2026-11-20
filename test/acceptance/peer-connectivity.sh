#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
. test/helpers/devnet-env.sh

if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

set -a
# shellcheck disable=SC1091
. test/devnet/versions.env
set +a

SOAK_SECONDS="${PEER_CONNECTIVITY_SOAK_SECONDS:-30}"
COMPOSE=(docker compose -p peer-connectivity --env-file test/devnet/versions.env -f test/fixtures/three-node/compose.yaml)
${COMPOSE[@]} down -v --remove-orphans
EVIDENCE_DIR="$ROOT_DIR/.tools/test-results/peer-connectivity"
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
  local logs
  for _ in $(seq 1 "$attempts"); do
    logs="$("${COMPOSE[@]}" logs "$service" 2>/dev/null || true)"
    if grep -q -- "$pattern" <<< "$logs"; then
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
    | sed -n 's/.*PEER_CONNECTED peer=\([0-9a-f]\{4\}\).*/\1/p' \
    | sort -u \
    | wc -l \
    | tr -d ' '
}

wait_for_full_mesh() {
  local service count complete
  for _ in $(seq 1 30); do
    complete=true
    for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
      count="$(unique_peer_count "$service")"
      if [ "$count" -lt 2 ]; then
        complete=false
      fi
    done
    if [ "$complete" = true ]; then
      return 0
    fi
    sleep 1
  done
  for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
    echo "$service saw $(unique_peer_count "$service") unique peers, expected 2" >&2
    "${COMPOSE[@]}" logs "$service" >&2 || true
  done
  return 1
}

identity_prefix() {
  local service="$1"
  "${COMPOSE[@]}" logs "$service" \
    | sed -n 's/.*IDENTITY_READY id=\([0-9a-f]\{4\}\).*/\1/p' \
    | head -1
}

peer_event_count() {
  local service="$1" event="$2" peer="$3"
  "${COMPOSE[@]}" logs "$service" \
    | awk -v marker="$event peer=$peer" 'index($0, marker) { count++ } END { print count+0 }'
}

wait_for_peer_event_growth() {
  local service="$1" event="$2" peer="$3" previous="$4" count
  for _ in $(seq 1 30); do
    count="$(peer_event_count "$service" "$event" "$peer")"
    if [ "$count" -gt "$previous" ]; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $service to log a new $event for peer=$peer" >&2
  "${COMPOSE[@]}" logs "$service" >&2 || true
  return 1
}

./gradlew clean build :apps:pubsub-node:installDist
capture_cardano_versions

test/devnet/reset.sh
start_fresh_devnet | tee "$EVIDENCE_DIR/cardano-chain-tip.json"
test/devnet/status.sh | tee "$EVIDENCE_DIR/cardano-status.json"
test/devnet/fund-identities.sh | tee "$EVIDENCE_DIR/funded-address-balances.txt"

./scripts/registry/build.sh
./scripts/registry/deploy.sh > "$EVIDENCE_DIR/topic-registry-deployment.txt"
./scripts/replication/build.sh
./scripts/replication/deploy.sh > "$EVIDENCE_DIR/replication-registry-deployment.txt"

"${COMPOSE[@]}" up -d --build --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3

for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  wait_for_log "$service" "NODE_STARTED"
  wait_for_log "$service" "PEER_CONNECTED"
  wait_for_log "$service" "PONG_RECEIVED"
  wait_for_log "$service" "rttMs="
done

wait_for_full_mesh
for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  "${COMPOSE[@]}" logs "$service" > "$EVIDENCE_DIR/$service-connections-and-ping.log"
done

echo "Running peer connectivity soak for $SOAK_SECONDS seconds"
sleep "$SOAK_SECONDS"

if "${COMPOSE[@]}" logs pubsub-node-1 pubsub-node-2 pubsub-node-3 | grep -Ei "exception|error" | grep -Ev "protocol_error|Malformed protocol message"; then
  echo "Unexpected exception/error found during soak" >&2
  exit 1
fi

node_1_id="$(identity_prefix pubsub-node-1)"
node_2_id="$(identity_prefix pubsub-node-2)"
node_3_id="$(identity_prefix pubsub-node-3)"
test -n "$node_1_id" && test -n "$node_2_id" && test -n "$node_3_id"
test "$node_1_id" != "$node_2_id" && test "$node_1_id" != "$node_3_id" && test "$node_2_id" != "$node_3_id"
before="$("${COMPOSE[@]}" exec -T pubsub-node-1 sha256sum /data/identity/identity.json | awk '{print $1}')"
test -n "$before"
node_2_disconnects="$(peer_event_count pubsub-node-2 PEER_DISCONNECTED "$node_1_id")"
node_3_disconnects="$(peer_event_count pubsub-node-3 PEER_DISCONNECTED "$node_1_id")"
"${COMPOSE[@]}" stop pubsub-node-1
wait_for_peer_event_growth pubsub-node-2 PEER_DISCONNECTED "$node_1_id" "$node_2_disconnects"
wait_for_peer_event_growth pubsub-node-3 PEER_DISCONNECTED "$node_1_id" "$node_3_disconnects"
"${COMPOSE[@]}" logs pubsub-node-2 pubsub-node-3 > "$EVIDENCE_DIR/node-1-stop-detection.log"
node_1_to_2="$(peer_event_count pubsub-node-1 PEER_CONNECTED "$node_2_id")"
node_1_to_3="$(peer_event_count pubsub-node-1 PEER_CONNECTED "$node_3_id")"
node_2_to_1="$(peer_event_count pubsub-node-2 PEER_CONNECTED "$node_1_id")"
node_3_to_1="$(peer_event_count pubsub-node-3 PEER_CONNECTED "$node_1_id")"
"${COMPOSE[@]}" start pubsub-node-1
wait_for_peer_event_growth pubsub-node-1 PEER_CONNECTED "$node_2_id" "$node_1_to_2"
wait_for_peer_event_growth pubsub-node-1 PEER_CONNECTED "$node_3_id" "$node_1_to_3"
wait_for_peer_event_growth pubsub-node-2 PEER_CONNECTED "$node_1_id" "$node_2_to_1"
wait_for_peer_event_growth pubsub-node-3 PEER_CONNECTED "$node_1_id" "$node_3_to_1"
after="$("${COMPOSE[@]}" exec -T pubsub-node-1 sha256sum /data/identity/identity.json | awk '{print $1}')"
test -n "$after"
test "$before" = "$after"

wait_for_full_mesh
"${COMPOSE[@]}" logs pubsub-node-1 pubsub-node-2 pubsub-node-3 > "$EVIDENCE_DIR/restart-reconnection.log"

"${COMPOSE[@]}" down
test/devnet/stop.sh
test/devnet/reset.sh
start_fresh_devnet | tee "$EVIDENCE_DIR/reset-restart-chain-tip.json"
test/devnet/stop.sh

echo "Peer connectivity test passed" | tee "$EVIDENCE_DIR/final-result.txt"
