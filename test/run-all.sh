#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Every acceptance scenario shares the same devnet runtime and Docker volume.
# Refuse overlapping suite runs so one run cannot reset another run's network.
LOCK_DIR="$ROOT_DIR/.tools/test-suite.lock"
mkdir -p "$ROOT_DIR/.tools"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  lock_owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || printf 'unknown')"
  echo "Another test suite run holds $LOCK_DIR (PID $lock_owner)." >&2
  echo "If no suite is running, remove that lock directory and retry." >&2
  exit 1
fi
printf '%s\n' "$$" > "$LOCK_DIR/pid"
trap 'rm -rf "$LOCK_DIR"' EXIT

acceptance_tests=(
  peer-connectivity.sh
  topic-registry-policy.sh
  event-authorization-and-delivery.sh
  secure-peer-sampling.sh
  topic-navigation.sh
  topic-dissemination-repair.sh
  registry-replication-recovery.sh
  subscriber-local-catch-up.sh
)
if (( $# > 1 )); then
  echo "Usage: bash test/run-all.sh [first-acceptance-test.sh]" >&2
  exit 2
fi
first_test="${1:-${acceptance_tests[0]}}"
valid_test=0
for test_case in "${acceptance_tests[@]}"; do
  if [[ "$test_case" == "$first_test" ]]; then valid_test=1; fi
done
if (( ! valid_test )); then
  echo "Unknown acceptance test: $first_test" >&2
  exit 2
fi

if grep -qi microsoft /proc/version 2>/dev/null; then
  cmd.exe /c gradlew.bat -g .gradle-user-home test
else
  ./gradlew --gradle-user-home .gradle-user-home test
fi

node --test test/unit/*.test.mjs

started=0
for test_case in "${acceptance_tests[@]}"; do
  if [[ "$test_case" == "$first_test" ]]; then started=1; fi
  if (( ! started )); then continue; fi
  printf '\n===== %s =====\n' "$test_case"
  bash "test/acceptance/$test_case"
done
