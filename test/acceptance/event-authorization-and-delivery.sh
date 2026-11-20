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

COMPOSE=(docker compose -p event-authorization-delivery --env-file test/devnet/versions.env -f test/fixtures/three-node/compose.yaml)
${COMPOSE[@]} down -v --remove-orphans
EVIDENCE_DIR="$ROOT_DIR/.tools/test-results/event-authorization-and-delivery"
REGISTRY_RUNTIME_DIR="$ROOT_DIR/test/devnet/runtime/registry"
export REGISTRY_RUNTIME_DIR

rm -rf "$EVIDENCE_DIR" "$REGISTRY_RUNTIME_DIR"
mkdir -p "$EVIDENCE_DIR"

exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1

run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then
    cmd.exe /c gradlew.bat "$@"
  else
    ./gradlew "$@"
  fi
}

compose_logs() {
  local service="$1"
  local tail_lines="${2:-500}"
  if command -v timeout >/dev/null 2>&1; then
    timeout 15s "${COMPOSE[@]}" logs --no-color --tail="$tail_lines" "$service"
  else
    "${COMPOSE[@]}" logs --no-color --tail="$tail_lines" "$service"
  fi
}

wait_for_log() {
  local service="$1"
  local pattern="$2"
  local attempts="${3:-120}"
  local logs
  for _ in $(seq 1 "$attempts"); do
    logs="$(compose_logs "$service" 1000 2>/dev/null || true)"
    if grep -q -- "$pattern" <<< "$logs"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $pattern in $service logs" >&2
  compose_logs "$service" 1000 >&2 || true
  return 1
}

json_field() {
  node -e 'let data=""; process.stdin.on("data", c => data += c); process.stdin.on("end", () => console.log(JSON.parse(data)[process.argv[1]]));' "$1"
}

assert_event_accepted_once() {
  local service="$1"
  local event_id="$2"
  wait_for_log "$service" "EVENT_ACCEPTED eventId=$event_id" 60
  compose_logs "$service" 3000 > "$EVIDENCE_DIR/$service-events.log"
  local count
  count="$(grep -c "EVENT_ACCEPTED eventId=$event_id" "$EVIDENCE_DIR/$service-events.log" || true)"
  if [[ "$count" != "1" ]]; then
    echo "$service accepted eventId=$event_id $count times, expected once" >&2
    exit 1
  fi
}

assert_rejected() {
  local service="$1"
  local event_id="$2"
  local reason="$3"
  wait_for_log "$service" "EVENT_REJECTED reason=$reason eventId=$event_id" 60
}

run_gradle clean build :apps:pubsub-node:installDist

test/devnet/reset.sh
start_fresh_devnet | tee "$EVIDENCE_DIR/topic-registry-setup.json"
test/devnet/fund-identities.sh | tee "$EVIDENCE_DIR/funded-address-balances.txt"

./scripts/registry/build.sh
./scripts/registry/deploy.sh | tee "$EVIDENCE_DIR/validator-and-policy-identifiers.env"
./scripts/replication/build.sh
./scripts/replication/deploy.sh > "$EVIDENCE_DIR/replication-registry-deployment.txt"

"${COMPOSE[@]}" up -d --build --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3
for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  wait_for_log "$service" "NODE_STARTED"
  node test/helpers/registry-observation-assertions.mjs node-ready 3 "$service" \
    > "$EVIDENCE_DIR/$service-registry-readiness.json"
done

node1_publisher="$(node scripts/pubsub-node.mjs publisher-key-id --node 1)"
node2_publisher="$(node scripts/pubsub-node.mjs publisher-key-id --node 2)"
printf '%s\n' "$node1_publisher" | tee "$EVIDENCE_DIR/registered-publisher-key-id.txt"
printf '%s\n' "$node2_publisher" | tee "$EVIDENCE_DIR/unregistered-publisher-key-id.txt"

echo "Creating moderated topic"
moderated_topic="$(./scripts/registry/create-topic.sh node-1 moderated "$(bash ./test/helpers/registry/payment-key-hash.sh node-2)" - 3 3600 | tail -1)"
echo "$moderated_topic" | tee "$EVIDENCE_DIR/moderated-topic-id.txt"
./scripts/registry/add-publisher.sh --node node-1 "$moderated_topic" node-1 | tee "$EVIDENCE_DIR/add-event-publisher-transaction.txt"
./scripts/registry/query.sh --topic "$moderated_topic" | tee "$EVIDENCE_DIR/moderated-topic-after-publisher.json"
for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  node test/helpers/registry-observation-assertions.mjs node-topic-publisher 3 \
    "$moderated_topic" "$service" "$node1_publisher" \
    > "$EVIDENCE_DIR/$service-publisher-readiness.json"
done
for node_index in 1 2 3; do
  node scripts/pubsub-node.mjs subscribe --node "$node_index" --topic "$moderated_topic" \
    > "$EVIDENCE_DIR/pubsub-node-$node_index-moderated-subscription.json"
done
node test/helpers/registry-observation-assertions.mjs dissemination-ready 3 "$moderated_topic" \
  > "$EVIDENCE_DIR/moderated-dissemination-readiness.json"

first_response="$(node scripts/pubsub-node.mjs publish --node 1 --topic "$moderated_topic" --payload "event authorization sequence zero")"
printf '%s\n' "$first_response" | tee "$EVIDENCE_DIR/published-event-sequence-0.json"
first_event="$(printf '%s' "$first_response" | json_field eventId)"
first_sequence="$(printf '%s' "$first_response" | json_field sequenceNumber)"
test "$first_sequence" = "0"
assert_event_accepted_once pubsub-node-2 "$first_event"
assert_event_accepted_once pubsub-node-3 "$first_event"

second_response="$(node scripts/pubsub-node.mjs publish --node 1 --topic "$moderated_topic" --payload "event authorization sequence one")"
printf '%s\n' "$second_response" | tee "$EVIDENCE_DIR/published-event-sequence-1.json"
second_sequence="$(printf '%s' "$second_response" | json_field sequenceNumber)"
test "$second_sequence" = "1"

unauthorized_response="$(node scripts/pubsub-node.mjs publish --node 2 --topic "$moderated_topic" --payload "unauthorized" --force-broadcast)"
printf '%s\n' "$unauthorized_response" | tee "$EVIDENCE_DIR/unauthorized-publisher-event.json"
unauthorized_event="$(printf '%s' "$unauthorized_response" | json_field eventId)"
assert_rejected pubsub-node-1 "$unauthorized_event" "UNAUTHORIZED_PUBLISHER"

tampered_response="$(node scripts/pubsub-node.mjs publish --node 1 --topic "$moderated_topic" --payload "tampered" --tamper-signature --force-broadcast)"
printf '%s\n' "$tampered_response" | tee "$EVIDENCE_DIR/tampered-signature-event.json"
tampered_event="$(printf '%s' "$tampered_response" | json_field eventId)"
assert_rejected pubsub-node-2 "$tampered_event" "INVALID_SIGNATURE"

node scripts/pubsub-node.mjs inject --node 1 --file "$EVIDENCE_DIR/published-event-sequence-0.json" | tee "$EVIDENCE_DIR/duplicate-resend.json"
wait_for_log pubsub-node-2 "EVENT_DUPLICATE eventId=$first_event" 60
"${COMPOSE[@]}" restart pubsub-node-1
wait_for_log pubsub-node-1 "NODE_STARTED" 90
node test/helpers/registry-observation-assertions.mjs node-ready 3 pubsub-node-1 \
  > "$EVIDENCE_DIR/restart-registry-readiness.json"
node scripts/pubsub-node.mjs publish --node 1 --topic "$moderated_topic" --payload "after restart" | tee "$EVIDENCE_DIR/sequence-continuity-after-restart.json"
restart_sequence="$(json_field sequenceNumber < "$EVIDENCE_DIR/sequence-continuity-after-restart.json")"
test "$restart_sequence" = "3"

echo "Creating open topic"
open_topic="$(./scripts/registry/create-topic.sh node-1 open - - 1 600 | tail -1)"
echo "$open_topic" | tee "$EVIDENCE_DIR/open-topic-id.txt"
node test/helpers/registry-observation-assertions.mjs node-topic 3 "$open_topic" pubsub-node-2 \
  > "$EVIDENCE_DIR/pubsub-node-2-open-topic-readiness.json"
for node_index in 1 2 3; do
  node scripts/pubsub-node.mjs subscribe --node "$node_index" --topic "$open_topic" \
    > "$EVIDENCE_DIR/pubsub-node-$node_index-open-subscription.json"
done
node test/helpers/registry-observation-assertions.mjs dissemination-ready 3 "$open_topic" \
  > "$EVIDENCE_DIR/open-dissemination-readiness.json"
open_response="$(node scripts/pubsub-node.mjs publish --node 2 --topic "$open_topic" --payload "open topic event")"
printf '%s\n' "$open_response" | tee "$EVIDENCE_DIR/open-topic-publication.json"
open_event="$(printf '%s' "$open_response" | json_field eventId)"
assert_event_accepted_once pubsub-node-1 "$open_event"
assert_event_accepted_once pubsub-node-3 "$open_event"

./scripts/registry/delete-topic.sh node-1 "$open_topic" | tee "$EVIDENCE_DIR/delete-open-topic-transaction.txt"
sleep 3
./scripts/registry/query.sh --topic "$open_topic" | tee "$EVIDENCE_DIR/deleted-topic-datum.json"
deleted_response="$(node scripts/pubsub-node.mjs publish --node 2 --topic "$open_topic" --payload "deleted topic event" --force-broadcast)"
printf '%s\n' "$deleted_response" | tee "$EVIDENCE_DIR/deleted-topic-rejected-event.json"
deleted_event="$(printf '%s' "$deleted_response" | json_field eventId)"
assert_rejected pubsub-node-1 "$deleted_event" "INACTIVE_TOPIC"

for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  wait_for_log "$service" "PONG_RECEIVED" 60
  "${COMPOSE[@]}" logs "$service" > "$EVIDENCE_DIR/$service-final.log"
done

cp "$REGISTRY_RUNTIME_DIR/transactions.log" "$EVIDENCE_DIR/registry-transactions.log"
./scripts/registry/query.sh --all | tee "$EVIDENCE_DIR/final-registry-snapshot-with-tombstones.json"

"${COMPOSE[@]}" down
test/devnet/stop.sh

echo "Event authorization and delivery test passed" | tee "$EVIDENCE_DIR/final-result.txt"
