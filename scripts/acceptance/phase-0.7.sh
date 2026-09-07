#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.7"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
set -a
. "$ROOT_DIR/ops/infra/devnet/versions.env"
set +a
COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.7/compose.yaml)
PREVIOUS_COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.6/compose.yaml)
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/ops/infra/devnet/runtime/registry"
export REPLICATION_REGISTRY_STATE="$ROOT_DIR/ops/infra/devnet/runtime/replication-registry/servers.json"
run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then cmd.exe /c gradlew.bat -g .gradle-user-home "$@"
  else ./gradlew --gradle-user-home "$ROOT_DIR/.gradle-user-home" "$@"; fi
}
acceptance() { node scripts/acceptance/phase-0.7.mjs "$1" "$EVIDENCE_DIR" "${@:2}"; }
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi; }
wait_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 120); do
    "${COMPOSE[@]}" logs --no-color --tail=5000 "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1
    grep -q "$pattern" "$EVIDENCE_DIR/$service-current.log" && return
    sleep 1
  done
  echo "Missing $pattern from $service" >&2; return 1
}
finish() {
  local status=$?
  for type in pubsub-node replication-server; do
    for n in 1 2 3; do "${COMPOSE[@]}" logs --no-color "$type-$n" > "$EVIDENCE_DIR/$type-$n-final.log" 2>&1 || true; done
  done
  if [ "$status" != 0 ]; then echo "Phase 0.7 acceptance FAILED ($status)" > "$EVIDENCE_DIR/final-result.txt"; fi
}
if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop Linux engine is not available; start Docker before Phase 0.7 acceptance." >&2
  exit 1
fi
trap finish EXIT

run_gradle clean test :apps:pubsub-node:installDist :apps:replication-server:installDist
for n in 1 2 3; do
  "${PREVIOUS_COMPOSE[@]}" logs --no-color "pubsub-node-$n" \
    > "$EVIDENCE_DIR/archived-phase-0.6-node-$n.log" 2>&1 || true
done
"${PREVIOUS_COMPOSE[@]}" config > "$EVIDENCE_DIR/archived-phase-0.6-compose.yaml" 2>&1 || true
"${PREVIOUS_COMPOSE[@]}" stop pubsub-node-1 pubsub-node-2 pubsub-node-3 || true
"${COMPOSE[@]}" down -v --remove-orphans || true
docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/devnet/compose.yaml down -v --remove-orphans || true
acceptance prepare-devnet
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh > "$EVIDENCE_DIR/devnet-health.json"
ops/infra/devnet/scripts/fund-identities.sh > "$EVIDENCE_DIR/funded-address-balances.txt"
./scripts/registry/build.sh
./scripts/registry/deploy.sh > "$EVIDENCE_DIR/topic-registry-deployment.txt"
./scripts/replication/build.sh
./scripts/replication/deploy.sh > "$EVIDENCE_DIR/replication-registry-deployment.txt"

ids=()
for n in 1 2 3; do
  id="$(./scripts/replication/server-id.sh "ops/infra/devnet/keys/node-$n/payment.vkey")"
  ids+=("$id")
  ./scripts/replication/register-server.sh "$id" "node-$n" "replication-server-$n" 8100 0 100000 \
    > "$EVIDENCE_DIR/registration-$n.txt"
done
IFS=,; ids_csv="${ids[*]}"; unset IFS
./scripts/replication/query-servers.sh > "$EVIDENCE_DIR/on-chain-server-registrations.json"
current_epoch="$(ops/infra/devnet/scripts/status.sh | node -e 'let x=""; process.stdin.on("data",d=>x+=d).on("end",()=>console.log(JSON.parse(x).epoch))')"
epoch_length_ms="$(node -p "Number(process.env.CARDANO_TESTNET_SLOT_LENGTH || 2) * Number(process.env.CARDANO_TESTNET_EPOCH_LENGTH || 500) * 1000")"
epoch_zero_ms="$(node -p "Date.now() - Number('$current_epoch') * Number('$epoch_length_ms')")"
acceptance server-configs "$ROOT_DIR/ops/infra/devnet/runtime/phase-0.7" "$ids_csv" "$epoch_zero_ms" "$epoch_length_ms"

topic="$(./scripts/registry/create-topic.sh node-1 phase-0.7-open-topic node-2 - 2 3600 | tail -1)"
printf '%s\n' "$topic" > "$EVIDENCE_DIR/topic-id.txt"
"${COMPOSE[@]}" up -d --build --force-recreate replication-server-1 replication-server-2 replication-server-3
for n in 1 2 3; do wait_log "replication-server-$n" REPLICATION_SERVER_STARTED; done
acceptance membership "$ids_csv"
"${COMPOSE[@]}" up -d --build --force-recreate pubsub-node-1 pubsub-node-2 pubsub-node-3
for n in 1 2 3; do wait_log "pubsub-node-$n" PEER_SAMPLING_STARTED; acceptance subscribe "$n" "$topic"; done
for n in 1 2 3; do wait_log "pubsub-node-$n" DISSEMINATION_CYCLE; done

./scripts/events/publish.sh node-1 "$topic" phase-0.7-sequence-0 > "$EVIDENCE_DIR/published-sequence-0.json"
event_zero="$(node -e 'const x=require(process.argv[1]); console.log(x.eventId)' "$(host_path "$EVIDENCE_DIR/published-sequence-0.json")")"
for n in 1 2 3; do wait_log "pubsub-node-$n" "EVENT_ACCEPTED eventId=$event_zero"; done
responsible_id="$(acceptance replicas "$ids_csv" "$EVIDENCE_DIR/published-sequence-0.json" initial | tail -1)"
acceptance state 3 node-3-pre-offline-delivery-state
"${COMPOSE[@]}" stop pubsub-node-3

for sequence in 1 2 3; do
  file="$EVIDENCE_DIR/published-sequence-$sequence.json"
  ./scripts/events/publish.sh node-1 "$topic" "phase-0.7-sequence-$sequence" > "$file"
  event_id="$(node -e 'const x=require(process.argv[1]); console.log(x.eventId)' "$(host_path "$file")")"
  wait_log pubsub-node-2 "EVENT_ACCEPTED eventId=$event_id"
  acceptance replicas "$ids_csv" "$file" "offline-$sequence" >/dev/null
done
acceptance progress "$topic" 3
"${COMPOSE[@]}" start pubsub-node-3
acceptance node-ready 3
wait_log pubsub-node-3 PEER_SAMPLING_STARTED
acceptance recover "$topic" 3
acceptance state 3 node-3-post-recovery-delivery-state
"${COMPOSE[@]}" logs --no-color pubsub-node-3 > "$EVIDENCE_DIR/node-3-recovered-events.log"
test "$(grep -c 'EVENT_ACCEPTED .* sequenceNumber=[123] peerNodeId=recovery' "$EVIDENCE_DIR/node-3-recovered-events.log")" = 3

responsible_index=0
for n in 1 2 3; do [ "${ids[$((n-1))]}" = "$responsible_id" ] && responsible_index=$n; done
test "$responsible_index" -gt 0
acceptance health-snapshot replication-inventories-before-restart
"${COMPOSE[@]}" restart "replication-server-$responsible_index"
acceptance replication-ready "$responsible_index"
wait_log "replication-server-$responsible_index" REPLICATION_SERVER_STARTED
acceptance health-snapshot replication-inventories-after-restart
persist_request_count=0
for n in 1 2 3; do
  "${COMPOSE[@]}" logs --no-color "replication-server-$n" > "$EVIDENCE_DIR/replication-server-$n-final.log"
  count="$(grep -c EVENT_PERSIST_REQUESTED "$EVIDENCE_DIR/replication-server-$n-final.log" || true)"
  persist_request_count=$((persist_request_count + count))
done
printf '%s\n' "$persist_request_count" > "$EVIDENCE_DIR/publisher-persistence-request-count.txt"
test "$persist_request_count" = 4
for n in 1 2 3; do
  "${COMPOSE[@]}" logs --no-color "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-health.log"
  grep -q PONG_RECEIVED "$EVIDENCE_DIR/node-$n-health.log"
  grep -q NAVIGATION_CYCLE "$EVIDENCE_DIR/node-$n-health.log"
  grep -q DISSEMINATION_CYCLE "$EVIDENCE_DIR/node-$n-health.log"
  grep -q REGISTRY_SYNCED "$EVIDENCE_DIR/node-$n-health.log"
done
echo "Phase 0.7 acceptance passed" | tee "$EVIDENCE_DIR/final-result.txt"
