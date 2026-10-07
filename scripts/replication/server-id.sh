#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
vkey="${1:?verification key file is required}"
replication_registry_cli server-id "$(host_path "$vkey")"
