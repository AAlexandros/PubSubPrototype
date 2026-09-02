#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: publisher-key-id.sh <node>" >&2
  exit 2
fi

case "$1" in
  node-1|pubsub-node-1) port=8001 ;;
  node-2|pubsub-node-2) port=8002 ;;
  node-3|pubsub-node-3) port=8003 ;;
  *) echo "Unknown node: $1" >&2; exit 2 ;;
esac

curl --fail --silent --show-error "http://127.0.0.1:$port/v1/events/publisher-key-id" \
  | node -e 'let data=""; process.stdin.on("data", c => data += c); process.stdin.on("end", () => console.log(JSON.parse(data).publisherKeyId));'
