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
  node-[1-9]|node-[1-4][0-9]|node-50|pubsub-node-[1-9]|pubsub-node-[1-4][0-9]|pubsub-node-50)
    node_index="${node##*-}" ;;
  *) echo "Unknown node: $node" >&2; exit 2 ;;
esac

args=(publish --node "$node_index" --topic "$topic_id" --payload "$payload")
[[ "$base64_payload" == true ]] && args+=(--base64)
[[ "$force_broadcast" == true ]] && args+=(--force-broadcast)
[[ "$tamper_signature" == true ]] && args+=(--tamper-signature)
node "$ROOT_DIR/scripts/pubsub-node.mjs" "${args[@]}"
