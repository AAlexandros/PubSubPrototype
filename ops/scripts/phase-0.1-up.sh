#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

./gradlew clean :apps:pubsub-node:installDist
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh
ops/infra/devnet/scripts/fund-identities.sh
docker compose --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.1/compose.yaml up -d --build --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3
