#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/common.sh"
if [[ $# -gt 1 || ( $# -eq 1 && "${1:-}" != --no-init ) ]]; then
  echo "Usage: $0 [--no-init]" >&2
  exit 2
fi
cd "$ROOT_DIR"
if [[ -f "$COMPOSE_FILE" ]]; then compose down -v --remove-orphans; fi
# A generated Compose file can shrink from 50 nodes to 3. Down only removes
# volumes declared by the current file, so remove older volumes for this
# testbed project as well.
project="${PUBSUB_TESTBED_PROJECT:-phase-0-9}"
project_volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"
while IFS= read -r volume; do
  [[ -n "$volume" ]] || continue
  case "$volume" in
    "$project"_pubsub-node-*-identity|"$project"_replication-server-*-data)
      docker volume rm "$volume" >/dev/null
      ;;
    *)
      echo "Refusing to remove unexpected testbed volume: $volume" >&2
      exit 1
      ;;
  esac
done <<< "$project_volumes"
stop_conflicting_stacks
ops/infra/devnet/scripts/reset.sh "$@"
resolved="$(cd "$(dirname "$TESTBED_RUNTIME")" && pwd)/$(basename "$TESTBED_RUNTIME")"
[[ "$resolved" == "$ROOT_DIR/.tools/phase-0.9" ]] || { echo "Refusing to wipe unexpected path: $resolved" >&2; exit 1; }
rm -rf -- "$resolved"
echo "Testbed runtime state and Docker volumes were wiped; external experiment data was not touched"
