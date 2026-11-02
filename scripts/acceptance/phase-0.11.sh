#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.11"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
set -a
. "$ROOT_DIR/ops/infra/devnet/versions.env"
set +a
COMPOSE=(docker compose -p phase-0-11 --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.7/compose.yaml)
DEVNET_COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/devnet/compose.yaml)
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/ops/infra/devnet/runtime/registry"
export REPLICATION_REGISTRY_STATE="$ROOT_DIR/ops/infra/devnet/runtime/replication-registry/servers.json"
check() { node scripts/acceptance/phase-0.11.mjs "$1" "$EVIDENCE_DIR" "${@:2}"; }
setup_check() { node scripts/acceptance/phase-0.7.mjs "$1" "$EVIDENCE_DIR" "${@:2}"; }
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi; }
start_healthy_devnet() {
  local attempt
  for attempt in 1 2; do
    if ops/infra/devnet/scripts/start.sh && \
        ops/infra/devnet/scripts/wait-healthy.sh > "$EVIDENCE_DIR/devnet-health.json"; then
      return 0
    fi
    "${DEVNET_COMPOSE[@]}" logs --no-color cardano-node \
      > "$EVIDENCE_DIR/devnet-start-attempt-$attempt.log" 2>&1 || true
    if [ "$attempt" = 2 ] || ! grep -Fq 'node1 was unable to produce any blocks for 45s' \
        "$EVIDENCE_DIR/devnet-start-attempt-$attempt.log"; then
      echo "Cardano devnet startup failed on attempt $attempt" >&2
      return 1
    fi
    echo "Retrying Cardano devnet after its block-production timeout" >&2
    "${DEVNET_COMPOSE[@]}" down -v --remove-orphans
    setup_check prepare-devnet
  done
}
wait_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 120); do
    "${COMPOSE[@]}" logs --no-color --tail=5000 "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1
    grep -Fq "$pattern" "$EVIDENCE_DIR/$service-current.log" && return
    sleep 1
  done
  echo "Missing $pattern in $service" >&2
  return 1
}
finish() {
  local status=$?
  local compose_status=0
  local devnet_status=0
  trap - EXIT
  set +e
  for service in cardano-node replication-server-1 replication-server-2 replication-server-3 \
      pubsub-node-1 pubsub-node-2 pubsub-node-3; do
    "${COMPOSE[@]}" logs --no-color "$service" > "$EVIDENCE_DIR/$service-final.log" 2>&1
  done
  "${DEVNET_COMPOSE[@]}" logs --no-color cardano-node \
    > "$EVIDENCE_DIR/devnet-cardano-node-final.log" 2>&1

  "${COMPOSE[@]}" down --remove-orphans
  compose_status=$?
  "${DEVNET_COMPOSE[@]}" down --remove-orphans
  devnet_status=$?

  if [ "$status" = 0 ] && { [ "$compose_status" != 0 ] || [ "$devnet_status" != 0 ]; }; then
    status=1
  fi
  if [ "$status" = 0 ]; then
    echo "Phase 0.11 acceptance passed; containers stopped and removed" > "$EVIDENCE_DIR/final-result.txt"
  else
    echo "Phase 0.11 acceptance FAILED ($status); cleanup statuses: phase-0-11=$compose_status devnet=$devnet_status" \
      > "$EVIDENCE_DIR/final-result.txt"
  fi
  exit "$status"
}
if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop Linux engine is unavailable" >&2
  exit 1
fi
trap finish EXIT

if grep -qi microsoft /proc/version 2>/dev/null; then
  DEBUG= cmd.exe /c gradlew.bat -g .gradle-user-home clean test :apps:pubsub-node:installDist :apps:replication-server:installDist
else
  ./gradlew --gradle-user-home "$ROOT_DIR/.gradle-user-home" clean test :apps:pubsub-node:installDist :apps:replication-server:installDist
fi
"${COMPOSE[@]}" down -v --remove-orphans
"${DEVNET_COMPOSE[@]}" down -v --remove-orphans
setup_check prepare-devnet
start_healthy_devnet
ops/infra/devnet/scripts/fund-identities.sh > "$EVIDENCE_DIR/funded-address-balances.txt"
./scripts/registry/build.sh
./scripts/registry/deploy.sh > "$EVIDENCE_DIR/topic-registry-deployment.txt"
./scripts/replication/build.sh
./scripts/replication/deploy.sh > "$EVIDENCE_DIR/replication-registry-deployment.txt"

ids=()
for n in 1 2 3; do
  id="$(./scripts/replication/server-id.sh "ops/infra/devnet/keys/node-$n/payment.vkey")"
  if [[ ! "$id" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid server ID from node-$n verification key" >&2
    exit 1
  fi
  ids+=("$id")
done
IFS=,; ids_csv="${ids[*]}"; unset IFS
setup_check server-configs "$ROOT_DIR/ops/infra/devnet/runtime/phase-0.7" "$ids_csv"
for n in 1 2 3; do
  ./scripts/replication/register-server.sh "$ROOT_DIR/ops/infra/devnet/runtime/phase-0.7/server-$n.yaml" \
    > "$EVIDENCE_DIR/registration-$n.txt"
done
./scripts/replication/query-servers.sh > "$EVIDENCE_DIR/on-chain-server-registrations.json"

topic="$(./scripts/registry/create-topic.sh node-1 phase-0.11-open-topic "$(bash ./scripts/registry/payment-key-hash.sh node-2)" - 2 3600 | tail -1)"
printf '%s\n' "$topic" > "$EVIDENCE_DIR/topic-id.txt"
"${COMPOSE[@]}" up -d --build --force-recreate replication-server-1 replication-server-2 replication-server-3
for n in 1 2 3; do wait_log "replication-server-$n" REPLICATION_SERVER_STARTED; done
setup_check membership "$ids_csv"
"${COMPOSE[@]}" up -d --build --force-recreate pubsub-node-1 pubsub-node-2 pubsub-node-3
for n in 1 2 3; do
  wait_log "pubsub-node-$n" SAMPLING_STARTED
  setup_check subscribe "$n" "$topic"
done
for n in 1 2 3; do wait_log "pubsub-node-$n" DISSEMINATION_CYCLE; done

./scripts/events/publish.sh node-1 "$topic" phase-0.11-sequence-0 > "$EVIDENCE_DIR/published-sequence-0.json"
event_zero="$(node -e 'console.log(require(process.argv[1]).eventId)' "$(host_path "$EVIDENCE_DIR/published-sequence-0.json")")"
wait_log pubsub-node-3 "EVENT_ACCEPTED eventId=$event_zero"
setup_check replicas "$ids_csv" "$EVIDENCE_DIR/published-sequence-0.json" initial >/dev/null
check snapshot "$topic" live
"${COMPOSE[@]}" restart pubsub-node-3
setup_check node-ready 3
check snapshot "$topic" first-restart

"${COMPOSE[@]}" stop pubsub-node-3
for sequence in 1 2 3; do
  ./scripts/events/publish.sh node-1 "$topic" "phase-0.11-sequence-$sequence" \
    > "$EVIDENCE_DIR/published-sequence-$sequence.json"
  setup_check replicas "$ids_csv" "$EVIDENCE_DIR/published-sequence-$sequence.json" "offline-$sequence" >/dev/null
done
"${COMPOSE[@]}" start pubsub-node-3
setup_check node-ready 3
check catch-up "$topic"
check snapshot "$topic" caught-up
node scripts/pubsub-node.mjs events --node 3 --topic "$topic" > "$EVIDENCE_DIR/cli-events.json"
node scripts/pubsub-node.mjs event --node 3 --event "$event_zero" > "$EVIDENCE_DIR/cli-event.json"
"${COMPOSE[@]}" exec -T pubsub-node-3 sh -c 'find /data/identity/events -type f -name "*.json" | sort' \
  > "$EVIDENCE_DIR/node-3-event-file-inventory.txt"
test "$(wc -l < "$EVIDENCE_DIR/node-3-event-file-inventory.txt")" = 4
"${COMPOSE[@]}" logs --no-color pubsub-node-3 > "$EVIDENCE_DIR/structured-local-store-and-catch-up.log"
grep -Fq CATCH_UP_EVENT_STORED "$EVIDENCE_DIR/structured-local-store-and-catch-up.log"
grep -Fq LOCAL_EVENT_STORED "$EVIDENCE_DIR/structured-local-store-and-catch-up.log"

"${COMPOSE[@]}" restart pubsub-node-3
setup_check node-ready 3
check snapshot "$topic" second-restart
"${COMPOSE[@]}" exec -T pubsub-node-3 sh -c 'find /data/identity/events -type f -name "*.json" | sort' \
  > "$EVIDENCE_DIR/node-3-event-file-inventory-after-second-restart.txt"
test "$(wc -l < "$EVIDENCE_DIR/node-3-event-file-inventory-after-second-restart.txt")" = 4
printf '%s\n' 'No explicit recover command or endpoint was invoked during catch-up.' \
  > "$EVIDENCE_DIR/no-explicit-recovery.txt"
echo "Phase 0.11 acceptance checks completed"
