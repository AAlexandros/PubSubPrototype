#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ $# -ne 2 ]]; then
  echo "Usage: inject.sh <node> <event-json-file>" >&2
  exit 2
fi

case "$1" in
  node-[1-9]|node-[1-4][0-9]|node-50|pubsub-node-[1-9]|pubsub-node-[1-4][0-9]|pubsub-node-50)
    node_index="${1##*-}" ;;
  *) echo "Unknown node: $1" >&2; exit 2 ;;
esac

node "$ROOT_DIR/scripts/pubsub-node.mjs" inject --node "$node_index" --file "$2"
