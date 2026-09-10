#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
count="$(cat "$TESTBED_RUNTIME/node-count.txt" 2>/dev/null || echo 3)"
"$(dirname "$0")/down.sh"
"$(dirname "$0")/up.sh" --nodes "$count"
