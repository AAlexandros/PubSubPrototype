#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
if [[ "${1:-}" == "--node" ]]; then
  signer="${2:?signer is required}"
  topic_id="${3:?topicId is required}"
  node="${4:?node is required}"
  publisher="$("$ROOT_DIR/scripts/events/publisher-key-id.sh" "$node")"
else
  signer="${1:?signer is required}"
  topic_id="${2:?topicId is required}"
  publisher="${3:?publisher is required}"
  case "$publisher" in
    node-1|node-2|node-3|pubsub-node-1|pubsub-node-2|pubsub-node-3)
      publisher="$("$ROOT_DIR/scripts/events/publisher-key-id.sh" "$publisher")"
      ;;
  esac
fi
registry_cli add-publisher "$signer" "$topic_id" "$publisher"
