#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

if [ "${1:-}" = "--utxos" ]; then
  registry_cli utxos registry-deployer
elif [ "${1:-}" = "--all" ]; then
  registry_cli query registry-deployer --all
elif [ "${1:-}" = "--topic" ]; then
  registry_cli topic registry-deployer "${2:?topicId is required}"
else
  registry_cli query registry-deployer
fi
