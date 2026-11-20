#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
. test/helpers/devnet-env.sh
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi
EVIDENCE_DIR="$ROOT_DIR/.tools/test-results/topic-dissemination-repair"
mkdir -p "$EVIDENCE_DIR"


exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
COMPOSE=(docker compose -p topic-dissemination-repair --env-file test/devnet/versions.env -f test/fixtures/topic-dissemination-repair/compose.yaml)
${COMPOSE[@]} down -v --remove-orphans
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/test/devnet/runtime/registry"

run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then
    cmd.exe /c gradlew.bat -g .gradle-user-home "$@"
  else
    ./gradlew --gradle-user-home "$ROOT_DIR/.gradle-user-home" "$@"
  fi
}
assert_runtime() {
  local evidence_arg="$EVIDENCE_DIR"
  if command -v cygpath >/dev/null 2>&1; then evidence_arg="$(cygpath -w "$EVIDENCE_DIR")"; fi
  node test/helpers/dissemination-assertions.mjs "$1" "$evidence_arg" "${2:-}" "${3:-}"
}
wait_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 120); do
    "${COMPOSE[@]}" logs --no-color "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1
    if grep -q "$pattern" "$EVIDENCE_DIR/$service-current.log"; then return; fi
    sleep 1
  done
  echo "Missing $pattern from $service" >&2
  return 1
}
event_count() {
  local service="$1" event_id="$2"
  "${COMPOSE[@]}" logs --no-color "$service" | grep -c "EVENT_ACCEPTED eventId=$event_id" || true
}
finish() {
  local status=$?
  local cleanup_status=0
  trap - EXIT
  "${COMPOSE[@]}" logs --no-color replication-server-1 > "$EVIDENCE_DIR/replication-server-1-final.log" 2>&1 || true
  for n in 1 2 3; do
    "${COMPOSE[@]}" logs --no-color "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-final.log" 2>&1 || true
    grep 'DISSEMINATION_' "$EVIDENCE_DIR/node-$n-final.log" > "$EVIDENCE_DIR/node-$n-dissemination.log" || true
    grep 'EVENT_' "$EVIDENCE_DIR/node-$n-final.log" > "$EVIDENCE_DIR/node-$n-events.log" || true
  done
  "${COMPOSE[@]}" down -v --remove-orphans > "$EVIDENCE_DIR/cleanup.log" 2>&1 || cleanup_status=$?
  test/devnet/reset.sh --no-init >> "$EVIDENCE_DIR/cleanup.log" 2>&1 || cleanup_status=$?
  if [[ "$status" == 0 ]]; then status="$cleanup_status"; fi
  if [[ "$status" != 0 ]]; then echo "Topic dissemination repair test FAILED ($status)" > "$EVIDENCE_DIR/final-result.txt"; fi
  exit "$status"
}
trap finish EXIT

run_gradle build :apps:pubsub-node:installDist :apps:replication-server:installDist
cp test/fixtures/topic-dissemination-repair/node-*.yaml "$EVIDENCE_DIR/"
"${COMPOSE[@]}" config > "$EVIDENCE_DIR/bootstrap-compose.yaml"
"${COMPOSE[@]}" stop pubsub-node-1 pubsub-node-2 pubsub-node-3 || true
docker compose --env-file test/devnet/versions.env -f test/devnet/compose.yaml down
assert_runtime prepare-devnet
start_fresh_devnet > "$EVIDENCE_DIR/devnet-health.json"
test/devnet/fund-identities.sh > "$EVIDENCE_DIR/funded-address-balances.txt"
./scripts/registry/build.sh
./scripts/registry/deploy.sh > "$EVIDENCE_DIR/deployment.txt"
./scripts/replication/build.sh
./scripts/replication/deploy.sh > "$EVIDENCE_DIR/replication-registry-deployment.txt"
server_id="$(./scripts/replication/server-id.sh test/devnet/keys/node-1/payment.vkey)"
[[ "$server_id" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid replication server ID" >&2; exit 1; }
node test/helpers/replication-recovery-assertions.mjs server-configs "$EVIDENCE_DIR" \
  "$ROOT_DIR/test/devnet/runtime/topic-dissemination-repair" "$server_id"
./scripts/replication/register-server.sh \
  "$ROOT_DIR/test/devnet/runtime/topic-dissemination-repair/server-1.yaml" \
  > "$EVIDENCE_DIR/replication-server-registration.json"

topic_one="$(./scripts/registry/create-topic.sh node-1 topic-dissemination-repair-open-topic "$(bash ./test/helpers/registry/payment-key-hash.sh node-2)" - 1 3600 | tail -1)"
topic_two="$(./scripts/registry/create-topic.sh node-1 topic-dissemination-repair-isolated-topic "$(bash ./test/helpers/registry/payment-key-hash.sh node-2)" - 1 3600 | tail -1)"
printf '%s\n%s\n' "$topic_one" "$topic_two" > "$EVIDENCE_DIR/topic-ids.txt"

"${COMPOSE[@]}" up -d --build --force-recreate --no-deps \
  replication-server-1 pubsub-node-1 pubsub-node-2 pubsub-node-3
wait_log replication-server-1 REPLICATION_SERVER_STARTED
for n in 1 2 3; do wait_log "pubsub-node-$n" NODE_STARTED; done
assert_runtime replication-ready "$server_id"
for n in 1 2 3; do assert_runtime subscribe "$n" "$topic_one"; done
assert_runtime initial "$topic_one"
for n in 1 2 3; do wait_log "pubsub-node-$n" NAVIGATION_GOSSIP_RECEIVED; done
assert_runtime navigation-candidates "$topic_one"
assert_runtime converged "$topic_one" converged-dissemination-views

node scripts/pubsub-node.mjs publish --node 1 --topic "$topic_one" --payload topic-dissemination-repair-first-event > "$EVIDENCE_DIR/published-event-1.json"
event_one="$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync(0,"utf8")).eventId)' < "$EVIDENCE_DIR/published-event-1.json")"
wait_log pubsub-node-1 "PERSIST_RESULT eventId=$event_one result=stored"
for n in 2 3; do wait_log "pubsub-node-$n" "EVENT_ACCEPTED eventId=$event_one"; done
sleep 3
for n in 1 2 3; do test "$(event_count "pubsub-node-$n" "$event_one")" = 1; done
wait_log pubsub-node-1 "EVENT_DISSEMINATED topicId=$topic_one"
for n in 1 2 3; do
  event_count "pubsub-node-$n" "$event_one" > "$EVIDENCE_DIR/event-1-node-$n-accept-count.txt"
done

"${COMPOSE[@]}" stop pubsub-node-3
assert_runtime repair "$topic_one"
node scripts/pubsub-node.mjs publish --node 1 --topic "$topic_one" --payload dissemination-after-peer-repair > "$EVIDENCE_DIR/published-event-2.json"
event_two="$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync(0,"utf8")).eventId)' < "$EVIDENCE_DIR/published-event-2.json")"
wait_log pubsub-node-1 "PERSIST_RESULT eventId=$event_two result=stored"
wait_log pubsub-node-2 "EVENT_ACCEPTED eventId=$event_two"
test "$(event_count pubsub-node-2 "$event_two")" = 1
test "$(event_count pubsub-node-1 "$event_two")" = 1

"${COMPOSE[@]}" start pubsub-node-3
sleep 5
assert_runtime rejoin "$topic_one"
"${COMPOSE[@]}" logs --no-color pubsub-node-3 > "$EVIDENCE_DIR/node-3-restart.log"
if grep -Fq 'Exception in thread' "$EVIDENCE_DIR/node-3-restart.log"; then
  echo "pubsub-node-3 threw an exception during restart" >&2
  exit 1
fi
assert_runtime subscribe 1 "$topic_two"
assert_runtime subscribe 2 "$topic_two"
assert_runtime second-topic "$topic_two"
node scripts/pubsub-node.mjs publish --node 1 --topic "$topic_two" --payload topic-dissemination-repair-isolated > "$EVIDENCE_DIR/published-event-3.json"
event_three="$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync(0,"utf8")).eventId)' < "$EVIDENCE_DIR/published-event-3.json")"
wait_log pubsub-node-1 "PERSIST_RESULT eventId=$event_three result=stored"
wait_log pubsub-node-2 "EVENT_ACCEPTED eventId=$event_three"
sleep 3
test "$(event_count pubsub-node-2 "$event_three")" = 1
test "$(event_count pubsub-node-1 "$event_three")" = 1
test "$(event_count pubsub-node-3 "$event_three")" = 0

health_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sleep 7
for n in 1 2 3; do
  "${COMPOSE[@]}" logs --no-color --since "$health_since" "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-final-health.log"
  grep -q PONG_RECEIVED "$EVIDENCE_DIR/node-$n-final-health.log"
  grep -q NAVIGATION_CYCLE "$EVIDENCE_DIR/node-$n-final-health.log"
  grep -q SECURECYCLON_CYCLE "$EVIDENCE_DIR/node-$n-final-health.log"
done
echo 'Topic dissemination repair test passed' | tee "$EVIDENCE_DIR/final-result.txt"
