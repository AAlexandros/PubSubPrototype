#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

set -a
# shellcheck disable=SC1091
. ops/infra/devnet/versions.env
set +a

COMPOSE=(docker compose -p topic-registry-policy --env-file ops/infra/devnet/versions.env -f ops/infra/phase-0.1/compose.yaml)
${COMPOSE[@]} down -v --remove-orphans
EVIDENCE_DIR="$ROOT_DIR/.tools/test-results/topic-registry-policy"
REGISTRY_RUNTIME_DIR="$ROOT_DIR/ops/infra/devnet/runtime/registry"
export REGISTRY_RUNTIME_DIR

rm -rf "$EVIDENCE_DIR" "$REGISTRY_RUNTIME_DIR"
mkdir -p "$EVIDENCE_DIR"

exec > >(tee "$EVIDENCE_DIR/acceptance-output.log") 2>&1

assert_topic_seen_by_all_nodes() {
  local topic_id="$1"
  for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
    echo "Waiting for $service to observe topicId=$topic_id"
    node test/helpers/registry-observation-assertions.mjs node-topic 3 "$topic_id" "$service" \
      > "$EVIDENCE_DIR/$service-topic-$topic_id.json"
  done
}

run_gradle() {
  if grep -qi microsoft /proc/version 2>/dev/null; then
    cmd.exe /c gradlew.bat "$@"
  else
    ./gradlew "$@"
  fi
}

run_gradle clean build :apps:pubsub-node:installDist

ops/infra/devnet/scripts/reset.sh
ops/infra/devnet/scripts/start.sh
ops/infra/devnet/scripts/wait-healthy.sh | tee "$EVIDENCE_DIR/cardano-chain-tip.json"
ops/infra/devnet/scripts/fund-identities.sh | tee "$EVIDENCE_DIR/funded-address-balances.txt"

./scripts/registry/build.sh
./scripts/registry/deploy.sh | tee "$EVIDENCE_DIR/validator-and-policy-identifiers.env"
./scripts/replication/build.sh
./scripts/replication/deploy.sh > "$EVIDENCE_DIR/replication-registry-deployment.txt"

"${COMPOSE[@]}" up -d --build --no-deps pubsub-node-1 pubsub-node-2 pubsub-node-3
for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  node test/helpers/registry-observation-assertions.mjs node-ready 3 "$service" \
    > "$EVIDENCE_DIR/$service-registry-readiness.json"
done

echo "Creating topic name=orders signer=node-1"
topic_id="$(./scripts/registry/create-topic.sh node-1 orders "$(bash ./scripts/registry/payment-key-hash.sh node-2)" - 2 3600 | tail -1)"
echo "$topic_id" | tee "$EVIDENCE_DIR/topic-creation-transaction.txt"
./scripts/registry/query.sh --utxos | tee "$EVIDENCE_DIR/topic-script-utxos-after-create.json"
./scripts/registry/query.sh --topic "$topic_id" | tee "$EVIDENCE_DIR/initial-topic-datum.json"
assert_topic_seen_by_all_nodes "$topic_id"

echo "Adding admin=node-2 signer=node-1 topicId=$topic_id"
./scripts/registry/add-admin.sh node-1 "$topic_id" "$(bash ./scripts/registry/payment-key-hash.sh node-2)" | tee "$EVIDENCE_DIR/add-admin-transaction.txt"
echo "Adding publisher=node-3 signer=node-2 topicId=$topic_id"
./scripts/registry/add-publisher.sh node-2 "$topic_id" node-3 | tee "$EVIDENCE_DIR/add-publisher-transaction.txt"
echo "Setting replicationFactor=3 signer=node-2 topicId=$topic_id"
./scripts/registry/set-replication-factor.sh node-2 "$topic_id" 3 | tee "$EVIDENCE_DIR/set-replication-factor-transaction.txt"
echo "Setting retentionPeriod=7200 signer=node-2 topicId=$topic_id"
./scripts/registry/set-retention-period.sh node-2 "$topic_id" 7200 | tee "$EVIDENCE_DIR/set-retention-period-transaction.txt"
./scripts/registry/query.sh --topic "$topic_id" | tee "$EVIDENCE_DIR/administration-final-topic-datum.json"

echo "Expecting unauthorized add-owner rejection signer=node-3 topicId=$topic_id"
if ./scripts/registry/add-owner.sh node-3 "$topic_id" "$(bash ./scripts/registry/payment-key-hash.sh node-3)" > "$EVIDENCE_DIR/rejected-unauthorized-transaction.txt" 2>&1; then
  echo "Unauthorized mutation unexpectedly succeeded" >&2
  exit 1
fi

echo "Expecting last-owner removal rejection signer=node-1 topicId=$topic_id"
if ./scripts/registry/remove-owner.sh node-1 "$topic_id" "$(bash ./scripts/registry/payment-key-hash.sh node-1)" > "$EVIDENCE_DIR/rejected-last-owner-removal.txt" 2>&1; then
  echo "Last owner removal unexpectedly succeeded" >&2
  exit 1
fi

echo "Expecting direct last-owner removal validator rejection signer=node-1 topicId=$topic_id"
if ./scripts/registry/direct-last-owner-removal.sh node-1 "$topic_id" "$(bash ./scripts/registry/payment-key-hash.sh node-1)" > "$EVIDENCE_DIR/rejected-direct-last-owner-removal.txt" 2>&1; then
  echo "Direct last-owner removal unexpectedly built successfully" >&2
  exit 1
fi

echo "Deleting topic signer=node-1 topicId=$topic_id"
./scripts/registry/delete-topic.sh node-1 "$topic_id" | tee "$EVIDENCE_DIR/delete-topic-transaction.txt"
sleep 3
./scripts/registry/query.sh --utxos | tee "$EVIDENCE_DIR/tombstone-script-utxos.json"
./scripts/registry/query.sh | tee "$EVIDENCE_DIR/active-snapshot-after-delete.json"
if grep -q "$topic_id" "$EVIDENCE_DIR/active-snapshot-after-delete.json"; then
  echo "Deleted topic is still active" >&2
  exit 1
fi
./scripts/registry/query.sh --topic "$topic_id" | tee "$EVIDENCE_DIR/deleted-topic-tombstone.json"
grep -q '"active" : false' "$EVIDENCE_DIR/deleted-topic-tombstone.json"

echo "Creating second topic name=payments signer=node-1"
second_topic_id="$(./scripts/registry/create-topic.sh node-1 payments - - 1 600 | tail -1)"
echo "$second_topic_id" | tee "$EVIDENCE_DIR/second-topic-creation-transaction.txt"
test "$topic_id" != "$second_topic_id"
assert_topic_seen_by_all_nodes "$second_topic_id"

for service in pubsub-node-1 pubsub-node-2 pubsub-node-3; do
  "${COMPOSE[@]}" logs "$service" > "$EVIDENCE_DIR/$service-registry-snapshot.log"
done

"${COMPOSE[@]}" restart pubsub-node-1
node test/helpers/registry-observation-assertions.mjs node-ready 3 pubsub-node-1 \
  > "$EVIDENCE_DIR/restart-registry-readiness.json"
"${COMPOSE[@]}" logs pubsub-node-1 > "$EVIDENCE_DIR/restart-cache-rebuild.log"

cp "$REGISTRY_RUNTIME_DIR/transactions.log" "$EVIDENCE_DIR/registry-transactions.log"
./scripts/registry/query.sh --all | tee "$EVIDENCE_DIR/final-registry-snapshot-with-tombstones.json"

"${COMPOSE[@]}" down
ops/infra/devnet/scripts/stop.sh

echo "Topic registry policy test passed" | tee "$EVIDENCE_DIR/final-result.txt"
