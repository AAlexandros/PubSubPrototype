#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.8"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
set -a
. "$ROOT_DIR/ops/infra/devnet/versions.env"
set +a
COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.8/compose.yaml)
PREVIOUS_COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.7/compose.yaml)
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/ops/infra/devnet/runtime/registry"
export REPLICATION_REGISTRY_STATE="$ROOT_DIR/ops/infra/devnet/runtime/replication-registry/servers.json"
run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then cmd.exe /c gradlew.bat -g .gradle-user-home "$@"
  else ./gradlew --gradle-user-home "$ROOT_DIR/.gradle-user-home" "$@"; fi
}
acceptance() { node scripts/acceptance/phase-0.8.mjs "$1" "$EVIDENCE_DIR" "${@:2}"; }
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi; }
wait_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 180); do
    "${COMPOSE[@]}" logs --no-color --tail=10000 "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1
    grep -q "$pattern" "$EVIDENCE_DIR/$service-current.log" && return
    sleep 1
  done
  echo "Missing $pattern from $service" >&2; return 1
}
finish() {
  local result=$?
  for type in pubsub-node replication-server; do
    for n in 1 2 3 4; do "${COMPOSE[@]}" logs --no-color "$type-$n" > "$EVIDENCE_DIR/$type-$n-final.log" 2>&1 || true; done
  done
  if [ "$result" != 0 ]; then echo "Phase 0.8 acceptance FAILED ($result)" > "$EVIDENCE_DIR/final-result.txt"; fi
}
if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop Linux engine is not available; start Docker before Phase 0.8 acceptance." >&2
  exit 1
fi
trap finish EXIT

run_gradle clean test :apps:pubsub-node:installDist :apps:replication-server:installDist
for type in pubsub-node replication-server; do
  for n in 1 2 3; do
    "${PREVIOUS_COMPOSE[@]}" logs --no-color "$type-$n" \
      > "$EVIDENCE_DIR/archived-phase-0.7-$type-$n.log" 2>&1 || true
  done
done
"${PREVIOUS_COMPOSE[@]}" config > "$EVIDENCE_DIR/archived-phase-0.7-compose.yaml" 2>&1 || true
"${PREVIOUS_COMPOSE[@]}" stop pubsub-node-1 pubsub-node-2 pubsub-node-3 \
  replication-server-1 replication-server-2 replication-server-3 || true
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

operators=(node-1 node-2 node-3 registry-deployer)
ids=()
for operator in "${operators[@]}"; do
  ids+=("$(./scripts/replication/server-id.sh "ops/infra/devnet/keys/$operator/payment.vkey")")
done
for n in 1 2 3; do
  ./scripts/replication/register-server.sh "${ids[$((n-1))]}" "${operators[$((n-1))]}" "replication-server-$n" 8100 0 100000 \
    > "$EVIDENCE_DIR/initial-registration-$n.txt"
done
./scripts/replication/query-servers.sh > "$EVIDENCE_DIR/initial-cardano-replication-membership.json"
current_epoch="$(ops/infra/devnet/scripts/status.sh | node -e 'let x=""; process.stdin.on("data",d=>x+=d).on("end",()=>console.log(JSON.parse(x).epoch))')"
epoch_length_ms="$(node -p "Number(process.env.CARDANO_TESTNET_SLOT_LENGTH || 2) * Number(process.env.CARDANO_TESTNET_EPOCH_LENGTH || 500) * 1000")"
epoch_zero_ms="$(node -p "Date.now() - Number('$current_epoch') * Number('$epoch_length_ms')")"
IFS=,; all_ids="${ids[*]}"; initial_ids="${ids[0]},${ids[1]},${ids[2]}"; unset IFS
acceptance server-configs "$ROOT_DIR/ops/infra/devnet/runtime/phase-0.8" "$all_ids" "$epoch_zero_ms" "$epoch_length_ms"

topic="$(./scripts/registry/create-topic.sh node-1 phase-0.8-open-topic node-2 - 2 3600 | tail -1)"
printf '%s\n' "$topic" > "$EVIDENCE_DIR/topic-id.txt"
"${COMPOSE[@]}" up -d --build --force-recreate replication-server-1 replication-server-2 replication-server-3
for n in 1 2 3; do wait_log "replication-server-$n" REPLICATION_SERVER_STARTED; done
acceptance membership "$initial_ids" 1,2,3 initial-membership-snapshots
"${COMPOSE[@]}" up -d --build --force-recreate pubsub-node-1 pubsub-node-2 pubsub-node-3
for n in 1 2 3; do wait_log "pubsub-node-$n" PEER_SAMPLING_STARTED; acceptance subscribe "$n" "$topic"; done
for n in 1 2 3; do wait_log "pubsub-node-$n" DISSEMINATION_CYCLE; done

for sequence in 0 1 2; do
  file="$EVIDENCE_DIR/published-sequence-$sequence.json"
  ./scripts/events/publish.sh node-1 "$topic" "phase-0.8-sequence-$sequence" > "$file"
  event_id="$(node -e 'const x=require(process.argv[1]); console.log(x.eventId)' "$(host_path "$file")")"
  wait_log pubsub-node-2 "EVENT_ACCEPTED eventId=$event_id"
  acceptance replicas "$all_ids" "$file" 2 1,2,3 "initial-event-$sequence" >/dev/null
done
failed_index="$(acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-0.json" 2 1,2,3 selected-failure-event | tail -1)"
failed_id="${ids[$((failed_index-1))]}"
remaining=""
for n in 1 2 3; do [ "$n" = "$failed_index" ] || remaining="${remaining:+$remaining,}$n"; done
acceptance state 3 node-3-pre-offline-delivery-state
"${COMPOSE[@]}" stop "replication-server-$failed_index"
acceptance failure-transition "$failed_id" "$remaining"
acceptance maintenance "$remaining" under-replicated-record-inventory
for sequence in 0 1 2; do acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-$sequence.json" 2 "$remaining" "repaired-event-$sequence" >/dev/null; done
acceptance maintenance "$remaining" repaired-maintenance-status
lookup_index="${remaining%%,*}"
acceptance lookup "$lookup_index" "$EVIDENCE_DIR/published-sequence-0.json" lookup-during-server-failure

"${COMPOSE[@]}" stop pubsub-node-3
for sequence in 3 4; do
  ./scripts/events/publish.sh node-1 "$topic" "phase-0.8-sequence-$sequence" > "$EVIDENCE_DIR/published-sequence-$sequence.json"
  acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-$sequence.json" 2 "$remaining" \
    "offline-repaired-event-$sequence" >/dev/null
done
"${COMPOSE[@]}" start pubsub-node-3
acceptance node-ready 3
acceptance recover "$topic" 3 2

"${COMPOSE[@]}" start "replication-server-$failed_index"
wait_log "replication-server-$failed_index" REPLICATION_SERVER_STARTED
acceptance recovered "$failed_id" 1,2,3
for sequence in 0 1 2 3 4; do acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-$sequence.json" 2 1,2,3 "post-rejoin-event-$sequence" >/dev/null; done

./scripts/replication/register-server.sh "${ids[3]}" registry-deployer replication-server-4 8100 0 100000 \
  > "$EVIDENCE_DIR/s4-registration-transaction.txt"
./scripts/replication/query-servers.sh > "$EVIDENCE_DIR/post-s4-cardano-membership.json"
"${COMPOSE[@]}" up -d --build --force-recreate replication-server-4
wait_log replication-server-4 REPLICATION_SERVER_STARTED
acceptance membership "$all_ids" 1,2,3,4 post-join-membership-snapshots
for sequence in 0 1 2 3 4; do acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-$sequence.json" 2 1,2,3,4 "post-join-event-$sequence" >/dev/null; done

./scripts/registry/set-replication-factor.sh node-1 "$topic" 3 > "$EVIDENCE_DIR/replication-factor-2-to-3-transaction.txt"
for sequence in 0 1 2 3 4; do acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-$sequence.json" 3 1,2,3,4 "factor-3-event-$sequence" >/dev/null; done
acceptance topic-log-factor "$topic" 3 1,2,3,4 factor-3-topic-log
./scripts/registry/set-replication-factor.sh node-1 "$topic" 2 > "$EVIDENCE_DIR/replication-factor-3-to-2-transaction.txt"
for sequence in 0 1 2 3 4; do acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-$sequence.json" 2 1,2,3,4 "factor-2-event-$sequence" >/dev/null; done
acceptance topic-log-factor "$topic" 2 1,2,3,4 factor-2-topic-log

./scripts/replication/unregister-server.sh "${ids[2]}" node-3 > "$EVIDENCE_DIR/server-unregistration-transaction.txt"
./scripts/replication/query-servers.sh > "$EVIDENCE_DIR/post-unregister-cardano-membership.json"
post_unregister_ids="${ids[0]},${ids[1]},${ids[3]}"
acceptance membership "$post_unregister_ids" 1,2,3,4 post-unregister-membership-snapshots
for sequence in 0 1 2 3 4; do acceptance replicas "$all_ids" "$EVIDENCE_DIR/published-sequence-$sequence.json" 2 1,2,3,4 "post-unregister-event-$sequence" >/dev/null; done
acceptance topic-log-factor "$topic" 2 1,2,3,4 final-topic-log-inventories
acceptance maintenance 1,2,3,4 final-maintenance-status
for n in 1 2 3; do
  "${COMPOSE[@]}" logs --no-color "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-health.log"
  grep -q PONG_RECEIVED "$EVIDENCE_DIR/node-$n-health.log"
  grep -q NAVIGATION_CYCLE "$EVIDENCE_DIR/node-$n-health.log"
  grep -q DISSEMINATION_CYCLE "$EVIDENCE_DIR/node-$n-health.log"
  grep -q REGISTRY_SYNCED "$EVIDENCE_DIR/node-$n-health.log"
done
echo "Phase 0.8 acceptance passed" | tee "$EVIDENCE_DIR/final-result.txt"
