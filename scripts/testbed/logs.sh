#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
compose logs --follow --tail="${TESTBED_LOG_TAIL:-200}" "$@"
