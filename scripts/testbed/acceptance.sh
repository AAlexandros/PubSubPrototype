#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../acceptance/phase-0.9.sh" "$@"
