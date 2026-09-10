#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
. "$ROOT_DIR/scripts/lib/platform.sh"
launcher="$ROOT_DIR/tools/telemetry/build/install/telemetry/bin/telemetry"

if [[ $# -lt 1 ]]; then
  echo "Usage: scripts/experiments/telemetry.sh <normalize|aggregate|dictionary> [path]" >&2
  exit 2
fi

if [[ ! -f "$launcher" ]]; then
  echo "Telemetry distribution is missing; installing it..." >&2
  pubsub_run_gradle "$ROOT_DIR" :tools:telemetry:installDist >&2
fi

# The generated POSIX launcher works in Linux, WSL, and Git Bash. Invoking it
# through bash also avoids Windows executable-bit differences on mounted drives.
exec bash "$launcher" "$@"
