#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"
. scripts/testbed/common.sh
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.10"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1

check() { node scripts/acceptance/phase-0.10.mjs "$@"; }
check_persistence() { node scripts/acceptance/phase-0.8.mjs "$@"; }
check_recovery() { node scripts/acceptance/phase-0.7.mjs "$@"; }
wait_for_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 180); do
    compose logs --no-color --tail=10000 "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1 || true
    if grep -Fq "$pattern" "$EVIDENCE_DIR/$service-current.log"; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for '$pattern' from $service" >&2
  return 1
}
devnet_compose() {
  docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/devnet/compose.yaml "$@"
}
devnet_compose_exec() {
  if pubsub_is_msys; then
    # Preserve container-side absolute paths while letting MSYS convert the
    # host-side Compose file arguments normally.
    MSYS2_ARG_CONV_EXCL='/devnet/runtime/testnet/socket' devnet_compose exec -T "$@"
  else
    devnet_compose exec -T "$@"
  fi
}
CARDANO_SOCKET_PATH=/devnet/runtime/testnet/socket/node1/sock
CARDANO_SOCKET_OFFLINE_PATH=/devnet/runtime/testnet/socket/node1/sock.phase-0.10-offline
CARDANO_SOCKET_HIDDEN=0
TESTBED_CLEANUP_REQUIRED=0
hide_cardano_socket() {
  devnet_compose_exec cardano-node mv "$CARDANO_SOCKET_PATH" "$CARDANO_SOCKET_OFFLINE_PATH"
  CARDANO_SOCKET_HIDDEN=1
}
restore_cardano_socket() {
  if [[ "$CARDANO_SOCKET_HIDDEN" == 1 ]]; then
    devnet_compose_exec cardano-node mv "$CARDANO_SOCKET_OFFLINE_PATH" "$CARDANO_SOCKET_PATH"
    CARDANO_SOCKET_HIDDEN=0
  fi
}
capture() {
  local exit_status=$?
  local cleanup_status=0
  local project generated_path remaining_containers remaining_volumes
  trap - EXIT
  restore_cardano_socket || true
  compose logs --no-color > "$EVIDENCE_DIR/integrated.log" 2>&1 || true
  scripts/replication/query-servers.sh --all > "$EVIDENCE_DIR/replication-registry.json" 2>&1 || true
  scripts/registry/query.sh --all > "$EVIDENCE_DIR/topic-registry.json" 2>&1 || true
  ops/infra/devnet/scripts/status.sh > "$EVIDENCE_DIR/chain-status.txt" 2>&1 || true
  if [[ "$TESTBED_CLEANUP_REQUIRED" == 1 ]]; then
    scripts/testbed/reset.sh --no-init > "$EVIDENCE_DIR/cleanup.log" 2>&1 || cleanup_status=$?
    if [[ "$cleanup_status" == 0 ]]; then
      for project in phase-0-9 devnet; do
        if ! remaining_containers="$(docker ps -aq --filter "label=com.docker.compose.project=$project" 2>> "$EVIDENCE_DIR/cleanup.log")"; then
          cleanup_status=1
        elif [[ -n "$remaining_containers" ]]; then
          echo "$project containers remain after cleanup: $remaining_containers" >> "$EVIDENCE_DIR/cleanup.log"
          cleanup_status=1
        fi
        if ! remaining_volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$project" 2>> "$EVIDENCE_DIR/cleanup.log")"; then
          cleanup_status=1
        elif [[ -n "$remaining_volumes" ]]; then
          echo "$project volumes remain after cleanup: $remaining_volumes" >> "$EVIDENCE_DIR/cleanup.log"
          cleanup_status=1
        fi
      done
      for generated_path in "$TESTBED_RUNTIME" "$ROOT_DIR/ops/infra/devnet/runtime" \
        "$ROOT_DIR/ops/infra/devnet/keys" "$ROOT_DIR/ops/infra/devnet/state"; do
        if [[ -e "$generated_path" ]]; then
          echo "Generated state remains after cleanup: $generated_path" >> "$EVIDENCE_DIR/cleanup.log"
          cleanup_status=1
        fi
      done
    fi
  fi
  if [[ "$exit_status" == 0 && "$cleanup_status" == 0 ]]; then
    echo "Phase 0.10 acceptance passed" > "$EVIDENCE_DIR/final-result.txt"
  elif [[ "$exit_status" == 0 ]]; then
    echo "Phase 0.10 acceptance FAILED (cleanup exit $cleanup_status)" > "$EVIDENCE_DIR/final-result.txt"
    cat "$EVIDENCE_DIR/cleanup.log" >&2
    exit_status="$cleanup_status"
  else
    echo "Phase 0.10 acceptance FAILED ($exit_status)" > "$EVIDENCE_DIR/final-result.txt"
    if [[ "$cleanup_status" != 0 ]]; then cat "$EVIDENCE_DIR/cleanup.log" >&2; fi
  fi
  exit "$exit_status"
}
trap capture EXIT
echo "Phase 0.10 acceptance running" > "$EVIDENCE_DIR/final-result.txt"
docker info >/dev/null 2>&1 || { echo "Docker Desktop Linux engine is required" >&2; exit 1; }
TESTBED_CLEANUP_REQUIRED=1

run_gradle :libs:test --tests org.pubsub.prototype.registry.RegistryObserverTest \
  --tests org.pubsub.prototype.registry.topic.cardano.CardanoRegistryObservationTest \
  > "$EVIDENCE_DIR/invalid-utxo-and-freshness-tests.log"
scripts/testbed/reset.sh
scripts/testbed/up.sh --nodes 3
compose config > "$EVIDENCE_DIR/compose.yaml"
cp "$TESTBED_RUNTIME/config/node-1.yaml" "$EVIDENCE_DIR/node-config.yaml"
cp "$TESTBED_RUNTIME/config/server-1.yaml" "$EVIDENCE_DIR/server-config.yaml"
check ready 3 > "$EVIDENCE_DIR/initial-readiness.json"

publisher1="$(scripts/events/publisher-key-id.sh node-1)"
publisher2="$(scripts/events/publisher-key-id.sh node-2)"
topic="$(scripts/registry/create-topic.sh node-1 phase-0.10-topic - "$publisher1" 2 3600 | tail -1)"
printf '%s\n' "$topic" > "$EVIDENCE_DIR/topic-id.txt"
scripts/registry/query.sh --utxos > "$EVIDENCE_DIR/topic-created-utxos.json"
check topic 3 "$topic" active > "$EVIDENCE_DIR/topic-created-observations.json"
if scripts/events/publish.sh node-2 "$topic" before-authorization > "$EVIDENCE_DIR/publish-before-add.json" 2>&1; then
  echo "Unlisted publisher was accepted" >&2; exit 1
fi

scripts/registry/set-replication-factor.sh node-1 "$topic" 3
scripts/registry/query.sh --utxos > "$EVIDENCE_DIR/topic-policy-updated-utxos.json"
check topic 3 "$topic" factor:3 > "$EVIDENCE_DIR/policy-update-observations.json"
scripts/registry/add-publisher.sh node-1 "$topic" "$publisher2"
scripts/registry/query.sh --utxos > "$EVIDENCE_DIR/publisher-added-utxos.json"
check topic 3 "$topic" "publisher:$publisher2" > "$EVIDENCE_DIR/publisher-added-observations.json"
scripts/events/publish.sh node-2 "$topic" after-authorization > "$EVIDENCE_DIR/publish-after-add.json"
scripts/registry/remove-publisher.sh node-1 "$topic" "$publisher2"
scripts/registry/query.sh --utxos > "$EVIDENCE_DIR/publisher-removed-utxos.json"
check topic 3 "$topic" "no-publisher:$publisher2" > "$EVIDENCE_DIR/publisher-removed-observations.json"
if scripts/events/publish.sh node-2 "$topic" after-revocation > "$EVIDENCE_DIR/publish-after-remove.json" 2>&1; then
  echo "Revoked publisher was accepted" >&2; exit 1
fi
scripts/registry/delete-topic.sh node-1 "$topic"
scripts/registry/query.sh --utxos > "$EVIDENCE_DIR/topic-deleted-utxos.json"
check topic 3 "$topic" deleted > "$EVIDENCE_DIR/topic-deleted-observations.json"

fourth_id="$(scripts/replication/server-id.sh ops/infra/devnet/keys/registry-deployer/payment.vkey)"
cat > "$EVIDENCE_DIR/server-4.yaml" <<EOF
server: {serverId: $fourth_id, advertisedHost: replication-server-4, port: 8100}
registration: {operator: registry-deployer, cliBackend: DEVNET, commitmentStartEpoch: 0, commitmentEndEpoch: 100000}
registry: {replicationRuntimeDir: /replication-registry, pollIntervalMs: 5000, maximumAgeMs: 30000, topicRegistryRuntimeDir: /registry, topicRegistrySigner: registry-deployer, topicRegistryCliBackend: HOST}
timing: {connectionTimeoutMs: 500, requestTimeoutMs: 1500, retries: 0, cleanupIntervalMs: 1000, failureProbeAttempts: 3, failureProbeTimeoutMs: 500, maintenanceIntervalMs: 500}
EOF
scripts/replication/register-server.sh "$EVIDENCE_DIR/server-4.yaml"
scripts/replication/query-servers.sh --all > "$EVIDENCE_DIR/member-added-chain.json"
check member 3 "$fourth_id" present > "$EVIDENCE_DIR/member-added-observations.json"
scripts/replication/unregister-server.sh "$EVIDENCE_DIR/server-4.yaml"
scripts/replication/query-servers.sh --all > "$EVIDENCE_DIR/member-removed-chain.json"
check member 3 "$fourth_id" absent > "$EVIDENCE_DIR/member-removed-observations.json"

printf '{"tampered":true}\n' > "$REGISTRY_RUNTIME_DIR/script-utxos.json"
printf '{"tampered":true}\n' > "$REPLICATION_REGISTRY_RUNTIME_DIR/servers.json"
compose restart pubsub-node-1 replication-server-1
check ready 3 > "$EVIDENCE_DIR/restart-with-tampered-cache.json"

if ! grep -q 'cliBackend: HOST' "$EVIDENCE_DIR/node-config.yaml" \
  || ! grep -q 'topicRegistryCliBackend: HOST' "$EVIDENCE_DIR/server-config.yaml" \
  || grep -Eq 'CACHE_ONLY|membershipPath:|script-utxos\.json|servers\.json' \
    "$EVIDENCE_DIR/node-config.yaml" "$EVIDENCE_DIR/server-config.yaml" "$EVIDENCE_DIR/compose.yaml"; then
  echo "Testbed application configs do not prove direct Cardano registry polling" >&2
  exit 1
fi
check ready 3 > "$EVIDENCE_DIR/direct-registry-recovery-readiness.json"

recovery_topic="$(scripts/registry/create-topic.sh node-1 phase-0.10-recovery-topic - "$publisher1" 3 3600 | tail -1)"
printf '%s\n' "$recovery_topic" > "$EVIDENCE_DIR/recovery-topic-id.txt"
check topic 3 "$recovery_topic" active > "$EVIDENCE_DIR/recovery-topic-observations.json"
for index in 1 2 3; do
  check_recovery subscribe "$EVIDENCE_DIR" "$index" "$recovery_topic"
done
check dissemination-ready 3 "$recovery_topic" > "$EVIDENCE_DIR/recovery-overlay-readiness.json"

server_ids="$(node -e 'const fs=require("fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],"utf8")).serverIds.join(","))' "$TESTBED_RUNTIME/topology.json")"
publish_recovery_event() {
  local sequence="$1" file="$EVIDENCE_DIR/published-sequence-$1.json" event_id
  scripts/events/publish.sh node-1 "$recovery_topic" "phase-0.10-recovery-sequence-$sequence" > "$file"
  event_id="$(node -e 'const fs=require("fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],"utf8")).eventId)' "$file")"
  printf '%s\n' "$event_id"
}
wait_live_delivery() {
  local sequence="$1" target_node="$2" event_id="$3"
  wait_for_log "pubsub-node-$target_node" "EVENT_ACCEPTED eventId=$event_id"
  if ! grep -F "EVENT_ACCEPTED eventId=$event_id" "$EVIDENCE_DIR/pubsub-node-$target_node-current.log" \
    | grep -Fq "sequenceNumber=$sequence"; then
    echo "Unexpected sequence number for event $event_id on pubsub-node-$target_node" >&2
    return 1
  fi
}

for sequence in 0 1; do
  event_id="$(publish_recovery_event "$sequence")"
  wait_live_delivery "$sequence" 2 "$event_id"
  wait_live_delivery "$sequence" 3 "$event_id"
  check_persistence replicas "$EVIDENCE_DIR" "$server_ids" \
    "$EVIDENCE_DIR/published-sequence-$sequence.json" 3 1,2,3 \
    "recovery-initial-sequence-$sequence" >/dev/null
done

failed_index="$(check_persistence replicas "$EVIDENCE_DIR" "$server_ids" \
  "$EVIDENCE_DIR/published-sequence-0.json" 3 1,2,3 recovery-selected-failure | tail -1)"
failed_id="$(node -e 'const fs=require("fs"); const ids=JSON.parse(fs.readFileSync(process.argv[1],"utf8")).serverIds; process.stdout.write(ids[Number(process.argv[2])-1])' "$TESTBED_RUNTIME/topology.json" "$failed_index")"
remaining_indices=""
for index in 1 2 3; do
  if [[ "$index" != "$failed_index" ]]; then
    remaining_indices="${remaining_indices:+$remaining_indices,}$index"
  fi
done
printf '%s\n' "$failed_index" > "$EVIDENCE_DIR/recovery-failed-server-index.txt"
compose stop "replication-server-$failed_index"
check_persistence failure-transition "$EVIDENCE_DIR" "$failed_id" "$remaining_indices"

for sequence in 2 3; do
  event_id="$(publish_recovery_event "$sequence")"
  wait_live_delivery "$sequence" 2 "$event_id"
  check_persistence replicas "$EVIDENCE_DIR" "$server_ids" \
    "$EVIDENCE_DIR/published-sequence-$sequence.json" 3 "$remaining_indices" \
    "recovery-during-server-outage-sequence-$sequence" >/dev/null
  IFS=, read -r -a remaining_server_indices <<< "$remaining_indices"
  for index in "${remaining_server_indices[@]}"; do
    check_persistence lookup "$EVIDENCE_DIR" "$index" \
      "$EVIDENCE_DIR/published-sequence-$sequence.json" \
      "recovery-lookup-sequence-$sequence-server-$index"
  done
done

compose start "replication-server-$failed_index"
check_persistence recovered "$EVIDENCE_DIR" "$failed_id" 1,2,3
for sequence in 0 1 2 3; do
  check_persistence replicas "$EVIDENCE_DIR" "$server_ids" \
    "$EVIDENCE_DIR/published-sequence-$sequence.json" 3 1,2,3 \
    "recovery-after-server-rejoin-sequence-$sequence" >/dev/null
done
check ready 3 > "$EVIDENCE_DIR/registry-readiness-after-server-rejoin.json"

event_id="$(publish_recovery_event 4)"
wait_live_delivery 4 3 "$event_id"
compose stop pubsub-node-3
for sequence in 5 6 7; do
  event_id="$(publish_recovery_event "$sequence")"
  wait_live_delivery "$sequence" 2 "$event_id"
  check_persistence replicas "$EVIDENCE_DIR" "$server_ids" \
    "$EVIDENCE_DIR/published-sequence-$sequence.json" 3 1,2,3 \
    "recovery-offline-subscriber-sequence-$sequence" >/dev/null
done
compose start pubsub-node-3
check_recovery node-ready "$EVIDENCE_DIR" 3
check ready 3 > "$EVIDENCE_DIR/registry-readiness-before-missed-event-recovery.json"
check_recovery recover "$EVIDENCE_DIR" "$recovery_topic" 3 3 5 6 7
compose logs --no-color pubsub-node-3 > "$EVIDENCE_DIR/missed-event-recovery-subscriber.log"
for sequence in 5 6 7; do
  test "$(grep -Ec "EVENT_ACCEPTED .*sequenceNumber=$sequence peerNodeId=recovery" \
    "$EVIDENCE_DIR/missed-event-recovery-subscriber.log")" = 1
done
event_id="$(publish_recovery_event 8)"
wait_live_delivery 8 2 "$event_id"
wait_live_delivery 8 3 "$event_id"
compose logs --no-color pubsub-node-3 > "$EVIDENCE_DIR/post-recovery-live-dissemination.log"
if grep -F "EVENT_ACCEPTED eventId=$event_id" "$EVIDENCE_DIR/post-recovery-live-dissemination.log" \
  | grep -Fq 'peerNodeId=recovery'; then
  echo "Post-recovery event was delivered through recovery instead of live dissemination" >&2
  exit 1
fi
check ready 3 > "$EVIDENCE_DIR/registry-readiness-after-missed-event-recovery.json"

hide_cardano_socket
compose restart pubsub-node-1 replication-server-1
check not-ready 3 pubsub-node-1,replication-server-1 > "$EVIDENCE_DIR/startup-during-outage.json"
sleep 35
check stale 3 pubsub-node-2,pubsub-node-3,replication-server-2,replication-server-3 \
  > "$EVIDENCE_DIR/stale-during-outage.json"
check not-loaded 3 pubsub-node-1,replication-server-1 \
  > "$EVIDENCE_DIR/not-loaded-during-outage.json"
restore_cardano_socket
ops/infra/devnet/scripts/wait-healthy.sh
check ready 3 > "$EVIDENCE_DIR/recovered-readiness.json"

scripts/testbed/up.sh --nodes 6
check ready 6 > "$EVIDENCE_DIR/scaled-readiness.json"
sleep 15
compose logs --no-color > "$EVIDENCE_DIR/scaled-integrated.log"
node -e 'const fs=require("fs"); const s=fs.readFileSync(process.argv[1],"utf8"); const lines=s.split(/\r?\n/).filter(x=>x.includes("REGISTRY_OBSERVED")); console.log(JSON.stringify({pollObservations:lines.length,perInstance:Object.fromEntries([...new Set(lines.map(x=>x.split(" ")[0]))].map(n=>[n,lines.filter(x=>x.startsWith(n)).length]))},null,2))' "$EVIDENCE_DIR/scaled-integrated.log" > "$EVIDENCE_DIR/query-load.json"
