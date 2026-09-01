#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
registry_cli remove-publisher "${1:?signer is required}" "${2:?topicId is required}" "${3:?publisher is required}"
