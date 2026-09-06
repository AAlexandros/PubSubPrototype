#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.5"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.5/compose.yaml)
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/ops/infra/devnet/runtime/registry"
run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then cmd.exe /c gradlew.bat "$@"; else ./gradlew "$@"; fi
}
assert_runtime() {
  local evidence_arg="$EVIDENCE_DIR"
  if command -v cygpath >/dev/null 2>&1; then evidence_arg="$(cygpath -w "$EVIDENCE_DIR")"; fi
  node scripts/acceptance/phase-0.5.mjs "$1" "$evidence_arg" "${2:-}" "${3:-}"
}
wait_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 90); do
    "${COMPOSE[@]}" logs --no-color --tail=4000 "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1
    if grep -q "$pattern" "$EVIDENCE_DIR/$service-current.log"; then return; fi
    sleep 1
  done
  echo "Missing $pattern from $service" >&2; return 1
}
finish() {
  local status=$?
  for n in 1 2 3; do
    "${COMPOSE[@]}" logs --no-color "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-final.log" 2>&1 || true
    grep 'NAVIGATION_' "$EVIDENCE_DIR/node-$n-final.log" > "$EVIDENCE_DIR/node-$n-navigation-gossip.log" || true
  done
  if [[ "$status" != 0 ]]; then echo "Phase 0.5 acceptance FAILED ($status)" > "$EVIDENCE_DIR/final-result.txt"; fi
}
trap finish EXIT

run_gradle build :apps:pubsub-node:installDist
cp ops/config/phase-0.5/node-*.yaml "$EVIDENCE_DIR/"
"${COMPOSE[@]}" config > "$EVIDENCE_DIR/bootstrap-compose.yaml"
# Fail if either leaf includes the other in its configured seed list (Phase 0.4 asymmetric bootstrap).
tr -d '\r' < ops/config/phase-0.5/node-1.yaml | grep -qx 'peers: \[\]'
for n in 2 3; do
  sed -n '/^peers:/,/^transport:/p' "ops/config/phase-0.5/node-$n.yaml" > "$EVIDENCE_DIR/node-$n-bootstrap.txt"
  test "$(grep -c 'host:' "$EVIDENCE_DIR/node-$n-bootstrap.txt")" = 1
  grep -q 'host: pubsub-node-1' "$EVIDENCE_DIR/node-$n-bootstrap.txt"
done

"${COMPOSE[@]}" stop pubsub-node-1 pubsub-node-2 pubsub-node-3 || true
docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/devnet/compose.yaml down
assert_runtime prepare-devnet
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh > "$EVIDENCE_DIR/devnet-health.json"
ops/infra/devnet/scripts/fund-identities.sh > "$EVIDENCE_DIR/funded-address-balances.txt"
./scripts/registry/build.sh
./scripts/registry/deploy.sh > "$EVIDENCE_DIR/deployment.txt"

# At least five active topics, created and owned by distinct signers.
declare -a TOPIC_IDS
for i in 1 2 3 4 5; do
  topic="$(./scripts/registry/create-topic.sh node-1 "phase-0.5-topic-$i" node-2 - 3 3600 | tail -1)"
  TOPIC_IDS+=("$topic")
done
printf '%s\n' "${TOPIC_IDS[@]}" > "$EVIDENCE_DIR/active-topic-ordering-input.txt"

"${COMPOSE[@]}" up -d --build --force-recreate --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3
for n in 1 2 3; do wait_log "pubsub-node-$n" PEER_SAMPLING_STARTED; done
for n in 1 2 3; do wait_log "pubsub-node-$n" NAVIGATION_STARTED; done

# Subscribe the three nodes to different topics.
assert_runtime subscribe 1 "${TOPIC_IDS[0]}"
assert_runtime subscribe 2 "${TOPIC_IDS[1]}"
assert_runtime subscribe 3 "${TOPIC_IDS[2]}"

for n in 1 2 3; do wait_log "pubsub-node-$n" SECURECYCLON_GOSSIP_RECEIVED; done
assert_runtime initial
assert_runtime converge
for n in 1 2 3; do wait_log "pubsub-node-$n" NAVIGATION_GOSSIP_RECEIVED; done
assert_runtime finger-topics
assert_runtime selection-quality

# Add a second subscription to node-1 and verify recompute without restart.
assert_runtime resubscribe-recompute 1 "${TOPIC_IDS[3]}"

# Node failure removes stale navigation links; restart permits rediscovery.
"${COMPOSE[@]}" stop pubsub-node-3
assert_runtime removed
"${COMPOSE[@]}" start pubsub-node-3
sleep 3
assert_runtime restarted

# Topic creation/deletion recomputes topic ordering and finger topics without restart.
new_topic="$(./scripts/registry/create-topic.sh node-1 phase-0.5-topic-created node-2 - 3 3600 | tail -1)"
assert_runtime topic-created "$new_topic"
./scripts/registry/delete-topic.sh node-1 "${TOPIC_IDS[4]}"
assert_runtime topic-deleted "${TOPIC_IDS[4]}"

# Phase 0.3 signed event handling and Phase 0.4 SecureCyclon remain operational after convergence.
sleep 4
./scripts/events/publish.sh node-2 "${TOPIC_IDS[1]}" 'phase-0.5-after-convergence' > "$EVIDENCE_DIR/published-event.json"
event_id="$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync(0,"utf8")).eventId)' < "$EVIDENCE_DIR/published-event.json")"
for n in 1 3; do wait_log "pubsub-node-$n" "EVENT_ACCEPTED eventId=$event_id"; done
health_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sleep 7
for n in 1 2 3; do
  "${COMPOSE[@]}" logs --no-color --since "$health_since" "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-health-after-convergence.log"
  grep -q PONG_RECEIVED "$EVIDENCE_DIR/node-$n-health-after-convergence.log"
done
echo 'Phase 0.5 acceptance passed' | tee "$EVIDENCE_DIR/final-result.txt"
