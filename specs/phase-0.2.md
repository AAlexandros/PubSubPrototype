# Phase 0.2 — Topic Registry on Cardano

## Goal

Implement the D2 Topic Registry on the Phase 0.1 Cardano devnet and expose it to the Java runtime through a stable registry API.

Phase 0.2 is complete when topic creation, administration, deletion, authorization, and three-node registry observation work end-to-end.

## Architecture

Add:

```text
contracts/topic-registry/
libs/registry-api/
libs/registry-cardano/
scripts/registry/
scripts/acceptance/phase-0.2.sh
implementation-reports/evidence/phase-0.2/
```

Use:

- **Aiken** for the Cardano validator/minting policy.
- Existing Phase 0.1 `cardano-testnet`, `cardano-cli`, funded identities, and runtime configuration.
- Java 21 for registry models and adapters.

## Topic state

Each topic shall have one on-chain state UTxO.

Logical state:

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

Rules:

- `topicId` is a permanent 256-bit identifier.
- At least one owner must always exist.
- Empty `publishers[]` means an open topic.
- Non-empty `publishers[]` means a moderated topic.
- `replicationFactor > 0`.
- `retentionPeriod > 0`.

Administrators are explicit state because D2 uses them in its administrative API.

## Topic identity

Use a unique topic token to identify each topic state UTxO.

Prototype realization:

1. `createTopic` consumes a unique transaction input as the creation seed.
2. The minting policy creates exactly one unique topic token.
3. Derive the 256-bit `topicId` deterministically from the unique token/creation identity.
4. The token remains associated with that topic for its lifetime.
5. Deleting a topic creates an inactive tombstone rather than making the identifier reusable.

The same `topicId` must never be assigned to another topic.

## Authorization

Use Cardano transaction signers for administrative authorization.

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

- unauthorized state changes;
- removal of the last owner;
- modification of `topicId`;
- invalid replication factor or retention period;
- transition from an inactive topic back to active.

## Registry operations

Implement D2-equivalent operations:

```text
createTopic(name, admins[], publishers[], R, T)
deleteTopic(topicId)

addOwner(topicId, owner)
removeOwner(topicId, owner)

addAdmin(topicId, admin)
removeAdmin(topicId, admin)

addPublisher(topicId, publisher)
removePublisher(topicId, publisher)

setReplicationFactor(topicId, R)
setRetentionPeriod(topicId, T)
```

Transactions may be constructed through `cardano-cli` in this phase.

## Contract deployment

Provide repeatable scripts for:

```text
scripts/registry/build.sh
scripts/registry/deploy.sh
scripts/registry/create-topic.sh
scripts/registry/delete-topic.sh
scripts/registry/add-owner.sh
scripts/registry/remove-owner.sh
scripts/registry/add-admin.sh
scripts/registry/remove-admin.sh
scripts/registry/add-publisher.sh
scripts/registry/remove-publisher.sh
scripts/registry/set-replication-factor.sh
scripts/registry/set-retention-period.sh
scripts/registry/query.sh
```

`deploy.sh` must export registry runtime values, including the validator address and topic minting policy ID.

## Java registry API

Add immutable models:

```text
TopicId
TopicState
RegistrySnapshot
```

Expose a Cardano-independent interface similar to:

```java
public interface TopicRegistry {
    RegistrySnapshot snapshot();
    Optional<TopicState> topic(TopicId topicId);

    TopicId createTopic(CreateTopicRequest request);
    void deleteTopic(TopicId topicId);

    void addOwner(TopicId topicId, String owner);
    void removeOwner(TopicId topicId, String owner);

    void addAdmin(TopicId topicId, String admin);
    void removeAdmin(TopicId topicId, String admin);

    void addPublisher(TopicId topicId, String publisher);
    void removePublisher(TopicId topicId, String publisher);

    void setReplicationFactor(TopicId topicId, int replicationFactor);
    void setRetentionPeriod(TopicId topicId, long retentionPeriod);
}
```

The protocol layers must depend only on `registry-api`, not directly on Cardano tooling.

## Cardano adapter

`registry-cardano` shall:

- read Cardano network settings from `infra/devnet/runtime/network.env`;
- query registry-controlled topic UTxOs;
- decode topic datums into `TopicState`;
- return only active topics in the normal registry snapshot;
- allow direct lookup of inactive/tombstoned topics;
- submit administrative transactions using the appropriate Cardano identity;
- wait for submitted transactions to become observable before reporting success.

CLI/subprocess integration is acceptable for Phase 0.2.

## Registry synchronization

Each of the three Pub/Sub nodes shall maintain a local cached registry snapshot.

Implement configurable polling of Cardano state.

On change:

```text
Cardano state
     ↓
registry-cardano
     ↓
RegistrySnapshot
     ↓
node local cache
```

Log at least:

```text
REGISTRY_SYNCED
TOPIC_CREATED
TOPIC_UPDATED
TOPIC_DELETED
REGISTRY_TX_REJECTED
```

A node restart must rebuild its registry cache from Cardano.

## Tests

### Contract tests

Verify:

- topic creation;
- creator becomes an owner;
- initial admins/publishers/R/T are stored correctly;
- owner operations succeed;
- admin publisher/R/T operations succeed;
- unauthorized mutations fail;
- last owner cannot be removed;
- deleted topic cannot be reactivated;
- `topicId` cannot change or be reused.

### Java tests

Verify:

- datum encode/decode;
- snapshot equality;
- active-topic filtering;
- tombstone lookup;
- cache rebuild;
- adapter command/response parsing.

## Acceptance scenario

Create:

```bash
./scripts/acceptance/phase-0.2.sh
```

It shall:

1. Reset and start the Phase 0.1 devnet.
2. Verify funded identities.
3. Build and deploy the Topic Registry.
4. Start the three Pub/Sub nodes.
5. Create a topic as `node-1`.
6. Verify all three nodes observe the same topic state.
7. Add `node-2` as admin.
8. Use `node-2` to add `node-3` as publisher.
9. Update replication factor and retention period.
10. Attempt an unauthorized mutation and verify rejection.
11. Attempt to remove the last owner and verify rejection.
12. Delete the topic as an owner.
13. Verify all three nodes remove it from the active snapshot.
14. Verify its tombstone remains queryable.
15. Create another topic and verify its `topicId` differs from the deleted topic.
16. Restart one Pub/Sub node and verify it rebuilds the same registry snapshot.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.2/
```

Include:

- contract build/deployment output;
- validator and policy identifiers;
- topic creation transaction;
- initial topic datum;
- successful administration transactions;
- rejected unauthorized transaction evidence;
- deleted topic tombstone;
- three-node registry snapshots;
- restart/cache-rebuild evidence;
- final acceptance result.

## Completion criterion

Phase 0.2 is complete when:

```bash
./scripts/acceptance/phase-0.2.sh
```

passes from a clean Phase 0.1 environment without manual state changes.
