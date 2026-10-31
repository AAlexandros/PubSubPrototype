#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"

node_count="${PUBSUB_NODE_COUNT:-3}"
if [[ "${1:-}" == "--nodes" ]]; then node_count="${2:?--nodes requires a count}"; shift 2; fi
current="$(cat "$TESTBED_RUNTIME/node-count.txt" 2>/dev/null || true)"
if [[ ! -f "$COMPOSE_FILE" || ! -f "$TESTBED_RUNTIME/topology.json" || "$current" != "$node_count" ]]; then
  scripts/testbed/bootstrap.sh "$node_count"
else
  ops/infra/devnet/scripts/start.sh
  ops/infra/devnet/scripts/wait-healthy.sh
  run_gradle :apps:pubsub-node:installDist :apps:replication-server:installDist :tools:telemetry:installDist
  server_ids="$(node -e 'const fs = require("fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).serverIds.join(","))' "$TESTBED_RUNTIME/topology.json")"
  node scripts/testbed/generate.mjs "$ROOT_DIR" "$node_count" "$server_ids" >/dev/null
fi
compose up -d --build --remove-orphans
for index in 1 2 3; do wait_http "http://127.0.0.1:$((8100 + index))/v1/ops/health" "replication-server-$index"; done
for index in $(seq 1 "$node_count"); do wait_http "http://127.0.0.1:$((8000 + index))/v1/peer-sampling/view" "pubsub-node-$index"; done
echo "Phase 0.10 testbed processes are running ($node_count Pub/Sub nodes, 3 replication servers)"
