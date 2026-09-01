#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"

registry_cli deploy registry-deployer
cat "$REGISTRY_RUNTIME_DIR/deployment.env"
grep -q '^TOPIC_REGISTRY_VALIDATOR_ADDRESS=' "$REGISTRY_RUNTIME_DIR/deployment.env"
grep -q '^TOPIC_POLICY_ID=' "$REGISTRY_RUNTIME_DIR/deployment.env"
