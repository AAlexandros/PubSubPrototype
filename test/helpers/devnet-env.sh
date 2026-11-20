#!/usr/bin/env bash
# Environment for registry operator commands exercised by local acceptance tests.
export CARDANO_CLI_BACKEND=DEVNET
export REGISTRY_RUNTIME_DIR="$ROOT_DIR/test/devnet/runtime/registry"
export REPLICATION_REGISTRY_RUNTIME_DIR="$ROOT_DIR/test/devnet/runtime/replication-registry"

# Acceptance scenarios call this only during fresh devnet setup. Cardano's
# single-node testnet can occasionally exit before producing its first block.
start_fresh_devnet() {
  local attempt startup_log
  for attempt in 1 2; do
    if test/devnet/start.sh 1>&2 && test/devnet/wait-healthy.sh; then
      return 0
    fi
    startup_log="$(docker compose \
        --env-file test/devnet/versions.env -f test/devnet/compose.yaml \
        logs --no-color cardano-node 2>/dev/null || true)"
    if [ "$attempt" = 2 ] || [[ "$startup_log" != *'node1 was unable to produce any blocks for 45s'* ]]; then
      echo "Cardano devnet startup failed on attempt $attempt" >&2
      return 1
    fi
    echo 'Retrying fresh Cardano devnet after its block-production timeout' >&2
    test/devnet/reset.sh 1>&2
  done
}
