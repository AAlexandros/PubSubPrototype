#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi
EVIDENCE_DIR="$ROOT_DIR/implementation-reports/evidence/phase-0.4"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
COMPOSE=(docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.4/compose.yaml)
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/ops/infra/devnet/runtime/registry"
run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then cmd.exe /c gradlew.bat "$@"; else ./gradlew "$@"; fi
}
assert_runtime() {
  local evidence_arg="$EVIDENCE_DIR"
  if command -v cygpath >/dev/null 2>&1; then evidence_arg="$(cygpath -w "$EVIDENCE_DIR")"; fi
  node scripts/acceptance/phase-0.4.mjs "$1" "$evidence_arg"
}
wait_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 90); do
    "${COMPOSE[@]}" logs --no-color --tail=2000 "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1
    if grep -q "$pattern" "$EVIDENCE_DIR/$service-current.log"; then return; fi
    sleep 1
  done
  echo "Missing $pattern from $service" >&2; return 1
}
finish() {
  local status=$?
  for n in 1 2 3; do
    "${COMPOSE[@]}" logs --no-color "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-final.log" 2>&1 || true
    grep 'SECURECYCLON_' "$EVIDENCE_DIR/node-$n-final.log" > "$EVIDENCE_DIR/node-$n-gossip.log" || true
  done
  grep 'SECURECYCLON_EXCHANGE_REJECTED' "$EVIDENCE_DIR/node-1-final.log" > "$EVIDENCE_DIR/invalid-exchange-rejection.log" || true
  if [[ "$status" != 0 ]]; then echo "Phase 0.4 acceptance FAILED ($status)" > "$EVIDENCE_DIR/final-result.txt"; fi
}
trap finish EXIT

run_gradle build :apps:pubsub-node:installDist
cp ops/config/phase-0.4/node-*.yaml "$EVIDENCE_DIR/"
"${COMPOSE[@]}" config > "$EVIDENCE_DIR/bootstrap-compose.yaml"
# Fail if either leaf includes the other in its configured seed list.
grep -qx 'peers: \[\]' ops/config/phase-0.4/node-1.yaml
for n in 2 3; do
  sed -n '/^peers:/,/^transport:/p' "ops/config/phase-0.4/node-$n.yaml" > "$EVIDENCE_DIR/node-$n-bootstrap.txt"
  test "$(grep -c 'host:' "$EVIDENCE_DIR/node-$n-bootstrap.txt")" = 1
  grep -q 'host: pubsub-node-1' "$EVIDENCE_DIR/node-$n-bootstrap.txt"
done
"${COMPOSE[@]}" stop pubsub-node-1 pubsub-node-2 pubsub-node-3
docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/devnet/compose.yaml down
assert_runtime prepare-devnet
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh > "$EVIDENCE_DIR/devnet-health.json"
ops/infra/devnet/scripts/fund-identities.sh > "$EVIDENCE_DIR/funded-address-balances.txt"
./scripts/registry/build.sh
./scripts/registry/deploy.sh > "$EVIDENCE_DIR/deployment.txt"

"${COMPOSE[@]}" up -d --build --force-recreate --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3
for n in 1 2 3; do wait_log "pubsub-node-$n" PEER_SAMPLING_STARTED; done
assert_runtime initial
assert_runtime converge
for n in 1 2 3; do
  wait_log "pubsub-node-$n" SECURECYCLON_GOSSIP_RECEIVED
  "${COMPOSE[@]}" logs --no-color "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-discovery.log"
done
"${COMPOSE[@]}" stop pubsub-node-3
assert_runtime removed
"${COMPOSE[@]}" start pubsub-node-3
sleep 3
assert_runtime restarted
assert_runtime invalid
wait_log pubsub-node-1 'SECURECYCLON_EXCHANGE_REJECTED.*reason=Request requires one fresh sender link first'

# Exercise the existing on-chain registry and signed Phase 0.3 event path after convergence.
topic="$(./scripts/registry/create-topic.sh node-1 open node-2 - 3 3600 | tail -1)"
echo "$topic" > "$EVIDENCE_DIR/event-topic-id.txt"
sleep 4
./scripts/events/publish.sh node-2 "$topic" 'phase-0.4-after-convergence' > "$EVIDENCE_DIR/published-event.json"
event_id="$(node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync(0,"utf8")).eventId)' < "$EVIDENCE_DIR/published-event.json")"
for n in 1 3; do wait_log "pubsub-node-$n" "EVENT_ACCEPTED eventId=$event_id"; done
health_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sleep 7
for n in 1 2 3; do
  "${COMPOSE[@]}" logs --no-color --since "$health_since" "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-health-after-convergence.log"
  grep -q PONG_RECEIVED "$EVIDENCE_DIR/node-$n-health-after-convergence.log"
done
echo 'Phase 0.4 acceptance passed' | tee "$EVIDENCE_DIR/final-result.txt"
