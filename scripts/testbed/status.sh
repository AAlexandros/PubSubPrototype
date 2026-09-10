#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
echo "Cardano devnet:"
ops/infra/devnet/scripts/status.sh || true
if [[ ! -f "$COMPOSE_FILE" ]]; then echo "Testbed: not bootstrapped"; exit 1; fi
echo "Compose processes:"
compose ps
count="$(cat "$TESTBED_RUNTIME/node-count.txt" 2>/dev/null || echo 0)"
for index in 1 2 3; do
  curl --silent --show-error "http://127.0.0.1:$((8100 + index))/v1/health" || true
  echo
done
for index in $(seq 1 "$count"); do
  curl --silent --show-error "http://127.0.0.1:$((8000 + index))/v1/peer-sampling/view" || true
  echo
done
