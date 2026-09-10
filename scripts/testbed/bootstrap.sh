#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"

node_count="${1:-${PUBSUB_NODE_COUNT:-3}}"
[[ "$node_count" =~ ^[0-9]+$ ]] && (( node_count >= 3 && node_count <= 50 )) || {
  echo "node count must be an integer from 3 through 50" >&2; exit 2;
}
docker info >/dev/null 2>&1 || { echo "Docker Desktop Linux engine is required" >&2; exit 1; }

mkdir -p "$TESTBED_RUNTIME"
stop_conflicting_stacks
run_gradle :apps:pubsub-node:installDist :apps:replication-server:installDist :tools:telemetry:installDist
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh
ops/infra/devnet/scripts/fund-identities.sh

if [[ ! -f "$REGISTRY_RUNTIME_DIR/deployment.env" ]]; then
  scripts/registry/build.sh
  scripts/registry/deploy.sh
fi
if [[ ! -f "$(dirname "$REPLICATION_REGISTRY_STATE")/deployment.env" ]]; then
  scripts/replication/build.sh
  scripts/replication/deploy.sh
fi

operators=(node-1 node-2 node-3)
ids=()
for operator in "${operators[@]}"; do
  ids+=("$(scripts/replication/server-id.sh "ops/infra/devnet/keys/$operator/payment.vkey")")
done
scripts/replication/query-servers.sh >/dev/null 2>&1 || true
for index in 0 1 2; do
  if [[ ! -f "$REPLICATION_REGISTRY_STATE" ]] || ! grep -q "${ids[$index]}" "$REPLICATION_REGISTRY_STATE"; then
    scripts/replication/register-server.sh "${ids[$index]}" "${operators[$index]}" "replication-server-$((index + 1))" 8100 0 100000
  fi
done
scripts/replication/query-servers.sh > "$TESTBED_RUNTIME/replication-membership.json"
IFS=,; ids_csv="${ids[*]}"; unset IFS
node scripts/testbed/generate.mjs "$ROOT_DIR" "$node_count" "$ids_csv" >/dev/null
printf '%s\n' "$node_count" > "$TESTBED_RUNTIME/node-count.txt"
echo "Phase 0.9 testbed bootstrapped: $node_count Pub/Sub nodes, 3 replication servers, registries ready"
