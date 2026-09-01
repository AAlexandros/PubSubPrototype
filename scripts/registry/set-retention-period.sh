#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
registry_cli set-retention-period "${1:?signer is required}" "${2:?topicId is required}" "${3:?retention period is required}"
