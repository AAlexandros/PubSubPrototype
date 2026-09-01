# Phase 0.2.1 — On-Chain Topic Registry

## Goal

Replace the Phase 0.2 file-backed registry implementation with a real Cardano on-chain Topic Registry.

Keep the existing Java `registry-api`, node polling/cache, registry scripts, and acceptance structure. Replace the persistence and authorization layer with Cardano UTxOs and Aiken validators.

## Aiken contract

Implement under:

```text
contracts/topic-registry/
```

Required:

- topic state validator;
- unique topic-token minting policy;
- datum/redeemer definitions;
- deterministic contract build output;
- validator address and policy ID export.

## Topic state UTxO

Each topic shall be represented by one script UTxO containing:

```text
TopicState
  topicId
  name
  owners[]
  admins[]
  publishers[]
  replicationFactor
  retentionPeriod
  active
```

The UTxO must contain the unique token representing that topic.

## Topic identity

`createTopic` shall:

1. consume a unique transaction input as creation seed;
2. mint exactly one topic token;
3. derive a permanent 256-bit `topicId` from the unique token identity;
4. create the initial topic state UTxO.

The minting policy must prevent another token with the same topic identity from being created.

Deleted topic IDs must never become reusable.

## State transitions

Administrative changes shall consume the current topic UTxO and create the next topic UTxO with the same topic token and `topicId`.

Implement:

```text
createTopic
deleteTopic

addOwner
removeOwner

addAdmin
removeAdmin

addPublisher
removePublisher

setReplicationFactor
setRetentionPeriod
```

## On-chain authorization

The validator must enforce transaction signer authorization.

### Owner only

- `deleteTopic`
- `addOwner`
- `removeOwner`
- `addAdmin`
- `removeAdmin`

### Owner or admin

- `addPublisher`
- `removePublisher`
- `setReplicationFactor`
- `setRetentionPeriod`

The validator must reject:

- unauthorized transitions;
- removing the last owner;
- changing `topicId`;
- replacing or removing the topic token during an active transition;
- `replicationFactor <= 0`;
- `retentionPeriod <= 0`;
- reactivating an inactive topic;
- modifying an inactive topic.

## Deletion

Deletion shall transition the topic into an irreversible tombstone state:

```text
active = false
```

The tombstone must remain queryable and retain the permanent `topicId`.

## Registry Cardano adapter

Update `libs/registry-cardano` so Cardano is the source of truth.

Required:

- query registry script UTxOs from the running devnet;
- identify topic UTxOs by policy/token;
- decode inline datum into `TopicState`;
- submit topic creation and update transactions;
- sign with the selected Phase 0.1 Cardano identity;
- wait until submitted transactions are visible on-chain;
- return active topics in normal snapshots;
- allow direct tombstone lookup.

Remove runtime JSON/store files as authoritative registry state.

Local files may only be used for generated transaction artifacts, cached snapshots, and test evidence.

## Registry scripts

Update the existing `scripts/registry/*.sh` commands so they operate against the Cardano ledger.

Required commands:

```text
build.sh
deploy.sh
create-topic.sh
delete-topic.sh

add-owner.sh
remove-owner.sh

add-admin.sh
remove-admin.sh

add-publisher.sh
remove-publisher.sh

set-replication-factor.sh
set-retention-period.sh

query.sh
```

`deploy.sh` shall export at least:

```text
TOPIC_REGISTRY_VALIDATOR_ADDRESS
TOPIC_POLICY_ID
```

into the registry runtime environment.

## Java/node integration

Keep the existing `registry-api`.

The Pub/Sub nodes shall continue polling through `registry-cardano`, but snapshots must now be reconstructed from Cardano UTxOs.

Verify:

- all three nodes observe the same on-chain registry state;
- node restart rebuilds registry state from Cardano;
- local cache loss does not lose registry state.

## Tests

### Aiken tests

Cover:

- valid creation;
- valid owner mutation;
- valid admin mutation;
- unauthorized signer;
- last-owner removal;
- invalid `R`;
- invalid `T`;
- topic ID mutation;
- token replacement/removal;
- tombstone reactivation;
- mutation after deletion.

### Java tests

Cover:

- datum encode/decode;
- Cardano CLI JSON parsing;
- token/topic lookup;
- active snapshot filtering;
- tombstone lookup;
- polling/cache rebuild.

## Acceptance

Update and run:

```bash
./scripts/acceptance/phase-0.2.sh
```

The acceptance scenario shall:

1. Reset/start the Cardano devnet.
2. Fund Phase 0.1 identities.
3. Build the Aiken contract.
4. Deploy/export validator and policy information.
5. Start the three Pub/Sub nodes.
6. Create a topic as `node-1`.
7. Verify the topic exists as an on-chain script UTxO.
8. Verify all three nodes observe the same topic state.
9. Add `node-2` as admin.
10. Use `node-2` to add `node-3` as publisher.
11. Update replication factor and retention period.
12. Submit an unauthorized mutation and verify ledger rejection.
13. Attempt removal of the last owner and verify ledger rejection.
14. Delete the topic.
15. Verify the topic disappears from active snapshots.
16. Verify the tombstone remains queryable on-chain.
17. Create another topic and verify it has a different `topicId`.
18. Restart one Pub/Sub node and verify it rebuilds the same registry snapshot from Cardano.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.2/
```

Include:

- Aiken build/test output;
- validator hash/address;
- topic policy ID;
- create transaction and resulting topic UTxO;
- topic datum after each successful mutation;
- rejected transaction evidence;
- tombstone UTxO;
- three-node snapshots;
- restart/cache-rebuild evidence;
- final acceptance result.

## Completion criterion

Phase 0.2.1 is complete when:

```bash
./scripts/acceptance/phase-0.2.sh
```

passes from a clean Phase 0.1 environment and the registry state and authorization rules are enforced by Cardano rather than by local registry files.
