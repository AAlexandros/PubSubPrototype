#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat >&2 <<'USAGE'
Usage: publish.sh <node> <topicId> <payload> [--base64] [--force-broadcast] [--tamper-signature]

Examples:
  publish.sh node-1 <topicId> "hello"
  publish.sh node-2 <topicId> "hello" --force-broadcast
  publish.sh node-1 <topicId> "hello" --tamper-signature --force-broadcast
USAGE
}

if [[ $# -lt 3 ]]; then
  usage
  exit 2
fi

node="$1"
topic_id="$2"
payload="$3"
shift 3

base64_payload=false
force_broadcast=false
tamper_signature=false
for arg in "$@"; do
  case "$arg" in
    --base64) base64_payload=true ;;
    --force-broadcast) force_broadcast=true ;;
    --tamper-signature) tamper_signature=true ;;
    *) usage; exit 2 ;;
  esac
done

case "$node" in
  node-1|pubsub-node-1) port=8001 ;;
  node-2|pubsub-node-2) port=8002 ;;
  node-3|pubsub-node-3) port=8003 ;;
  *) echo "Unknown node: $node" >&2; exit 2 ;;
esac

if [[ "$base64_payload" == false ]]; then
  payload="$(printf '%s' "$payload" | base64 | tr -d '\r\n')"
fi

query="forceBroadcast=$force_broadcast&tamperSignature=$tamper_signature"
body="$(node -e 'process.stdout.write(JSON.stringify({topicId: process.argv[1], payload: process.argv[2]}))' "$topic_id" "$payload")"
curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:$port/v1/events/publish?$query" \
  --data "$body"
