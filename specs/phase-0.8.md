# Phase 0.8 — Replication Server Resilience and Replica Maintenance

## Goal

Extend the Phase 0.7 persistence baseline with automatic replication-server failure detection, replica repair, live membership changes, server joins/leaves, and dynamic replication-factor updates.

The persistence layer shall preserve the configured replication factor whenever enough active replication servers exist.

## D2 basis

D2 requires replication servers to:

- know the active replication-server membership from Cardano;
- monitor servers that share responsibility for stored records;
- confirm failures using repeated liveness checks;
- recompute responsible replica sets after membership changes;
- copy records to replacement servers;
- migrate responsibility when servers join or leave.

Phase 0.8 implements those maintenance behaviors.

## Architecture

Update:

```text
libs/persistence-core/
apps/replication-server/
apps/pubsub-node/
scripts/replication/
scripts/acceptance/
ops/config/
ops/infra/
```

Add evidence under:

```text
implementation-reports/evidence/phase-0.8/
```

Keep Phase 0.8 independent from:

- SecureCyclon;
- Navigation;
- Hybrid Dissemination.

Replication-server membership remains Cardano-backed.

## Monitoring set

For replication server `p`:

```text
Ep = records currently stored by p
Se = responsible replica set for record e
Mp = union of Se for all e in Ep
```

`p` shall monitor servers in `Mp`.

Monitoring applies to:

```text
event replicas
topic publisher-progress logs
```

Do not continuously probe unrelated replication servers only for failure detection.

## Failure detection

Use configurable active health probes.

Defaults:

```text
failureProbeAttempts = 3
failureProbeTimeoutMs = 5000
```

A server shall be considered unavailable only after the configured number of consecutive failed probes.

Temporary request failures must not immediately trigger replica migration.

Log:

```text
REPLICATION_PEER_SUSPECTED
REPLICATION_PEER_CONFIRMED_DOWN
REPLICATION_PEER_RECOVERED
```

## Failure notification

When server `p` confirms server `q` unavailable:

1. Identify records stored by `p` for which `q` was also responsible.
2. Recompute the responsible replica set excluding unavailable membership.
3. Identify replacement servers.
4. Notify the affected replacement servers.
5. Include enough information for them to locate surviving replicas.

Duplicate failure notifications must be idempotent.

## Replica repair

When a server becomes newly responsible for a record:

```text
new responsible server
        ↓
identify surviving replicas
        ↓
fetch record
        ↓
validate record
        ↓
store atomically
        ↓
acknowledge repair
```

For events, verify the stored `EventEnvelope` before accepting the repaired replica.

For topic logs, merge progress monotonically:

```text
latestSequenceNumber never decreases
latestTimestamp never decreases for the same sequence
```

Emit:

```text
REPLICA_REPAIR_STARTED
REPLICA_REPAIR_FETCHED
REPLICA_REPAIR_STORED
REPLICA_REPAIR_FAILED
REPLICA_REPAIR_COMPLETED
```

## Replication invariant

For each non-expired record:

```text
desiredReplicaCount =
    min(topic.replicationFactor, activeHealthyServerCount)
```

The system shall converge toward `desiredReplicaCount`.

Temporary over-replication is acceptable during migration.

Permanent under-replication is not acceptable while enough healthy servers exist.

## Server rejoin

If a previously unavailable server becomes reachable again:

- do not automatically restore its old assignments;
- recompute responsibility from the current active membership;
- only records for which it is currently responsible shall be restored to it.

A restarted server must keep its persistent `serverId`.

## Live Cardano membership changes

Replication servers shall continue polling the on-chain Replication Registry.

Membership changes must be applied without process restart.

### Server unregister

When a server becomes inactive on-chain:

```text
active membership changes
        ↓
responsible sets change
        ↓
replacement servers fetch required replicas
        ↓
old server is no longer used for new placement
```

### Server register

When a new server becomes active:

```text
new membership
        ↓
responsible sets change
        ↓
new server identifies records now assigned to it
        ↓
fetches them from surviving replicas
```

The joining server must not require a central coordinator.

## Join synchronization

A newly active server shall determine which existing records it should take responsibility for.

For the prototype, this may be implemented through a bounded metadata/inventory exchange between replication servers.

The protocol shall avoid transferring unrelated event payloads.

At minimum:

```text
eventKey
topicId
publisherKeyId
sequenceNumber
retention metadata
```

may be exchanged to determine responsibility before payload fetch.

## Replica release

After repair/migration has completed and sufficient replicas are confirmed, servers that are no longer responsible may delete their extra copy.

Deletion must not occur before the new responsible replica has been confirmed stored.

Expired records continue to follow normal Phase 0.7 retention cleanup.

## Dynamic replication-factor changes

Monitor Topic Registry changes.

If:

```text
R increases
```

then additional responsible servers shall receive replicas until the new factor is satisfied.

Example:

```text
R = 2
S1 + S2 store event

R -> 3

S3 receives replica
```

If:

```text
R decreases
```

servers no longer in the responsible set may remove excess replicas after the new responsible set is confirmed healthy.

Example:

```text
R = 3
S1 + S2 + S3

R -> 2

one no-longer-responsible copy may be released
```

The same rules apply to topic publisher-progress logs.

## Membership and placement consistency

All servers shall derive placement from:

```text
current active replication membership
topic replicationFactor
record key
```

The same deterministic Phase 0.7 placement algorithm remains authoritative.

Conflicting or stale membership snapshots must not silently produce destructive replica deletion.

Before deleting an old replica, use a membership snapshot at least as recent as the one used to create the replacement replica.

## APIs

Keep existing Phase 0.7 public APIs.

Add read-only operational endpoints:

```text
GET /v1/maintenance/status
GET /v1/maintenance/replicas
```

Return at least:

```text
serverId
active membership version
suspected peers
confirmed unavailable peers
repair queue
under-replicated records
```

Internal repair endpoints may be added as needed.

## Logging

Add structured events:

```text
REPLICATION_MEMBERSHIP_CHANGED
REPLICATION_PEER_SUSPECTED
REPLICATION_PEER_CONFIRMED_DOWN
REPLICATION_PEER_RECOVERED
REPLICA_UNDER_REPLICATED
REPLICA_REPAIR_STARTED
REPLICA_REPAIR_FETCHED
REPLICA_REPAIR_STORED
REPLICA_REPAIR_COMPLETED
REPLICA_REPAIR_FAILED
REPLICA_RELEASED
REPLICATION_FACTOR_CHANGED
SERVER_JOIN_SYNC_STARTED
SERVER_JOIN_SYNC_COMPLETED
```

Include where applicable:

```text
serverId
peerServerId
eventKey
topicId
oldReplicationFactor
newReplicationFactor
oldReplicaSet
newReplicaSet
reason
```

## Tests

### Failure detection

Verify:

- one failed probe does not confirm failure;
- configured consecutive failures confirm failure;
- successful probe clears suspicion;
- duplicate failure notifications are harmless.

### Replica repair

Verify:

- replacement server is computed deterministically;
- replacement fetches from a surviving replica;
- repaired event is validated;
- repaired topic log remains monotonic;
- repair is idempotent;
- failed repair remains queued/retryable.

### Membership changes

Verify:

- on-chain unregister changes placement;
- on-chain register changes placement;
- membership updates require no restart;
- newly responsible server receives assigned replicas;
- no-longer-responsible server releases only after confirmed replacement.

### Replication-factor changes

Verify:

- `R: 2 -> 3` creates the additional replica;
- `R: 3 -> 2` removes excess replicas safely;
- `R > activeServerCount` uses all active healthy servers;
- topic logs follow the same factor.

### Restart/rejoin

Verify:

- server identity survives restart;
- rejoined server uses current placement, not historical placement;
- stale failure state is cleared after confirmed recovery.

## Acceptance

Create:

```bash
./scripts/acceptance/phase-0.8.sh
```

Acceptance shall:

1. Start a fresh Cardano devnet.
2. Deploy Topic Registry and Replication Registry.
3. Start and register replication servers `S1`, `S2`, and `S3`.
4. Start the three Pub/Sub nodes.
5. Create an open topic with `R = 2`.
6. Publish several events.
7. Verify every event has exactly two responsible replicas.
8. Select one server that is responsible for at least one tested event.
9. Stop that replication server.
10. Verify remaining servers first mark it suspected and only confirm failure after the configured probe policy.
11. Verify affected records become under-replicated.
12. Verify replacement servers automatically fetch and store the missing replicas.
13. Verify all affected records return to `R = 2`.
14. Verify lookup still succeeds while the failed server remains offline.
15. Take one Pub/Sub subscriber offline, publish events, restart it, and verify Phase 0.7 recovery still succeeds using the repaired persistence state.
16. Restart the failed replication server with the same identity.
17. Verify it is recognized as recovered and only takes responsibility for records assigned under the current membership.
18. Register a fourth replication server `S4` on-chain while the system is running.
19. Verify all existing replication servers observe the membership change without restart.
20. Verify `S4` receives records for which it becomes responsible.
21. Verify obsolete extra replicas are released only after migration succeeds.
22. Change the topic replication factor from `2` to `3`.
23. Verify all retained event records and topic logs converge to three replicas.
24. Change the topic replication factor from `3` to `2`.
25. Verify excess replicas are safely reduced to two.
26. Unregister one active replication server on-chain.
27. Verify membership updates and required replica migration complete without process restart.
28. Verify SecureCyclon, Navigation, Hybrid Dissemination, live publication, offline recovery, registry synchronization, and PING/PONG remain operational.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.8/
```

Include:

- initial Cardano replication membership;
- initial event-to-replica placement;
- failure-probe logs;
- confirmed-failure evidence;
- under-replicated record inventory;
- repair plan;
- repaired replica inventories;
- successful lookup during failure;
- offline-subscriber recovery after repair;
- failed-server restart/recovery;
- `S4` registration transaction;
- membership snapshots before/after join;
- join migration evidence;
- `R=2 -> R=3` evidence;
- `R=3 -> R=2` evidence;
- server unregistration transaction;
- post-unregister migration evidence;
- final replica inventories;
- final acceptance result.

## Completion criterion

Phase 0.8 is complete when:

```bash
./scripts/acceptance/phase-0.8.sh
```

passes from a clean environment and the persistence layer automatically restores the required replica count after server failures, joins/leaves, and replication-factor changes without restarting the system.
