#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../experiments/aggregate.sh" "${1:-$(cd "$(dirname "$0")/../.." && pwd)/results}"
