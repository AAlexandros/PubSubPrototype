#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: inject.sh <node> <event-json-file>" >&2
  exit 2
fi

case "$1" in
  node-1|pubsub-node-1) port=8001 ;;
  node-2|pubsub-node-2) port=8002 ;;
  node-3|pubsub-node-3) port=8003 ;;
  *) echo "Unknown node: $1" >&2; exit 2 ;;
esac

file="$2"
node_file="$file"
if command -v cygpath >/dev/null 2>&1 && node -p "process.platform" 2>/dev/null | grep -q '^win32$'; then
  node_file="$(cygpath -w "$file")"
fi

body="$(node -e 'const fs = require("fs"); const response = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); process.stdout.write(JSON.stringify(response.envelope || response));' "$node_file")"
curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:$port/v1/events/inject" \
  --data "$body"
