#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
launcher="$ROOT_DIR/tools/telemetry/build/install/telemetry/bin/telemetry"

if [[ $# -lt 1 ]]; then
  echo "Usage: scripts/experiments/telemetry.sh <normalize|aggregate|dictionary> [path]" >&2
  exit 2
fi

if [[ ! -f "$launcher" ]]; then
  echo "Telemetry distribution is missing; installing it..." >&2
  if grep -qi microsoft /proc/version 2>/dev/null || [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
    (cd "$ROOT_DIR" && cmd.exe /c gradlew.bat -g .gradle-user-home :tools:telemetry:installDist) >&2
  else
    "$ROOT_DIR/gradlew" --gradle-user-home "$ROOT_DIR/.gradle-user-home" :tools:telemetry:installDist >&2
  fi
fi

# The generated POSIX launcher works in Linux, WSL, and Git Bash. Invoking it
# through bash also avoids Windows executable-bit differences on mounted drives.
exec bash "$launcher" "$@"
