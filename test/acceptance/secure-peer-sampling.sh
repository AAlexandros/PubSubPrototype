#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
. test/helpers/devnet-env.sh
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
fi
EVIDENCE_DIR="$ROOT_DIR/.tools/test-results/secure-peer-sampling"
mkdir -p "$EVIDENCE_DIR"


exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1
COMPOSE=(docker compose -p secure-peer-sampling --env-file test/devnet/versions.env -f test/fixtures/secure-peer-sampling/compose.yaml)
DEVNET_COMPOSE=(docker compose --env-file test/devnet/versions.env -f test/devnet/compose.yaml)
${COMPOSE[@]} down -v --remove-orphans
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/test/devnet/runtime/registry"
run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then cmd.exe /c gradlew.bat "$@"; else ./gradlew "$@"; fi
}
assert_runtime() {
  local evidence_arg="$EVIDENCE_DIR"
  if command -v cygpath >/dev/null 2>&1; then evidence_arg="$(cygpath -w "$EVIDENCE_DIR")"; fi
  node test/helpers/peer-sampling-assertions.mjs "$1" "$evidence_arg"
}
start_healthy_devnet() {
  local attempt
  for attempt in 1 2; do
    if test/devnet/start.sh && \
        test/devnet/wait-healthy.sh > "$EVIDENCE_DIR/devnet-health.json"; then
      return 0
    fi
    "${DEVNET_COMPOSE[@]}" logs --no-color cardano-node \
      > "$EVIDENCE_DIR/devnet-start-attempt-$attempt.log" 2>&1 || true
    if [[ "$attempt" == 2 ]] || ! grep -Fq 'node1 was unable to produce any blocks for 45s' \
        "$EVIDENCE_DIR/devnet-start-attempt-$attempt.log"; then
      echo "Cardano devnet startup failed on attempt $attempt" >&2
      return 1
    fi
    echo "Retrying Cardano devnet after its block-production timeout" >&2
    "${DEVNET_COMPOSE[@]}" down -v --remove-orphans
    assert_runtime prepare-devnet
  done
}
wait_log() {
  local service="$1" pattern="$2"
  for _ in $(seq 1 90); do
    "${COMPOSE[@]}" logs --no-color "$service" > "$EVIDENCE_DIR/$service-current.log" 2>&1
    if grep -q "$pattern" "$EVIDENCE_DIR/$service-current.log"; then return; fi
    sleep 1
  done
  echo "Missing $pattern from $service" >&2; return 1
}
finish() {
  local status=$?
  local cleanup_status=0
  trap - EXIT
  for n in 1 2 3; do
    "${COMPOSE[@]}" logs --no-color "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-final.log" 2>&1 || true
    grep 'SECURECYCLON_' "$EVIDENCE_DIR/node-$n-final.log" > "$EVIDENCE_DIR/node-$n-gossip.log" || true
  done
  grep 'PEER_DISCONNECTED.*Invalid peer identity' "$EVIDENCE_DIR/node-1-final.log" > "$EVIDENCE_DIR/invalid-identity-rejection.log" || true
  "${COMPOSE[@]}" down -v --remove-orphans > "$EVIDENCE_DIR/cleanup.log" 2>&1 || cleanup_status=$?
  test/devnet/reset.sh --no-init >> "$EVIDENCE_DIR/cleanup.log" 2>&1 || cleanup_status=$?
  if [[ "$status" == 0 ]]; then status="$cleanup_status"; fi
  if [[ "$status" != 0 ]]; then echo "Secure peer sampling test FAILED ($status)" > "$EVIDENCE_DIR/final-result.txt"; fi
  exit "$status"
}
trap finish EXIT

run_gradle build :apps:pubsub-node:installDist
cp test/fixtures/secure-peer-sampling/node-*.yaml "$EVIDENCE_DIR/"
"${COMPOSE[@]}" config > "$EVIDENCE_DIR/bootstrap-compose.yaml"
# Fail if either leaf includes the other in its configured seed list.
grep -qx 'peers: \[\]' test/fixtures/secure-peer-sampling/node-1.yaml
for n in 2 3; do
  sed -n '/^peers:/,/^transport:/p' "test/fixtures/secure-peer-sampling/node-$n.yaml" > "$EVIDENCE_DIR/node-$n-bootstrap.txt"
  test "$(grep -c 'host:' "$EVIDENCE_DIR/node-$n-bootstrap.txt")" = 1
  grep -q 'host: pubsub-node-1' "$EVIDENCE_DIR/node-$n-bootstrap.txt"
done
"${COMPOSE[@]}" stop pubsub-node-1 pubsub-node-2 pubsub-node-3
docker compose --env-file test/devnet/versions.env -f test/devnet/compose.yaml down
assert_runtime prepare-devnet
start_healthy_devnet
test/devnet/fund-identities.sh > "$EVIDENCE_DIR/funded-address-balances.txt"
./scripts/registry/build.sh
./scripts/registry/deploy.sh > "$EVIDENCE_DIR/deployment.txt"
./scripts/replication/build.sh
./scripts/replication/deploy.sh > "$EVIDENCE_DIR/replication-registry-deployment.txt"

"${COMPOSE[@]}" up -d --build --force-recreate --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3
for n in 1 2 3; do wait_log "pubsub-node-$n" NODE_STARTED; done
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
wait_log pubsub-node-1 'PEER_DISCONNECTED.*Invalid peer identity'

health_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sleep 7
for n in 1 2 3; do
  "${COMPOSE[@]}" logs --no-color --since "$health_since" "pubsub-node-$n" > "$EVIDENCE_DIR/node-$n-health-after-convergence.log"
  grep -q PONG_RECEIVED "$EVIDENCE_DIR/node-$n-health-after-convergence.log"
done
echo 'Secure peer sampling test passed' | tee "$EVIDENCE_DIR/final-result.txt"
