# Phase 0.7 — Event Persistence Baseline

## Goal

Implement the first end-to-end version of the D2 Event Persistence layer.

A subscriber that is offline while events are published must be able to reconnect, determine which events it missed, and retrieve them from dedicated replication servers.

Phase 0.7 shall provide:

- on-chain replication-server registration;
- a small one-hop replication-server DHT;
- deterministic event storage keys;
- replicated event storage;
- per-topic publisher progress logs;
- event lookup;
- missed-event recovery.

## D2 basis

D2 separates live dissemination from persistence:

```text
Hybrid Dissemination
    → delivers events to online subscribers

Persistence Layer
    → retains events for offline subscribers
```

Replication servers form a relatively small and stable set whose membership and network addresses are globally known from Cardano.

For Phase 0.7, use **three replication servers**.

## Architecture

Add:

```text
contracts/replication-registry/
libs/persistence-api/
libs/persistence-core/
apps/replication-server/
scripts/replication/
scripts/acceptance/phase-0.7.sh
implementation-reports/evidence/phase-0.7/
```

The runtime becomes:

```text
                    Cardano
                  /         \
       Topic Registry     Replication Registry
              |                    |
              v                    v
        Pub/Sub nodes      Replication servers
              |                    |
              +------ events ------+
```

The persistence layer must remain independent from SecureCyclon, Navigation, and Hybrid Dissemination.

## Replication-server registry

Implement an Aiken-backed registry for replication servers.

Each active server registration shall contain at least:

```text
ReplicationServerState
  serverId
  operator
  host
  port
  commitmentStartEpoch
  commitmentEndEpoch
  active
```

Where:

- `serverId` is a permanent 256-bit identifier;
- `operator` identifies the Cardano account controlling the registration;
- `host` and `port` identify the replication service;
- commitment boundaries are expressed in Cardano epochs.

Prototype identity:

```text
serverId = SHA-256(encoded Cardano verification key)
```

Required operations:

```text
registerServer
unregisterServer
queryServers
```

Only the controlling Cardano identity may update or unregister its server record.

Every replication server and Pub/Sub node shall be able to obtain the active replication-server set from Cardano.

## Replication-server membership

The active server set is expected to be small.

Each replication server shall maintain the complete current active membership:

```text
serverId
host
port
```

Membership changes shall be picked up through configurable polling without process restart.

A server must not depend on SecureCyclon or Navigation to discover replication servers.

## 256-bit DHT space

Replication-server IDs and persistence keys belong to the same 256-bit identifier space.

For a key `k`, compute the responsible servers deterministically from the active server membership.

Prototype assignment rule:

1. Compute cyclic numeric distance between `k` and every active `serverId`.
2. Sort ascending by distance.
3. Break ties by `serverId`.
4. Select the first `R` servers.

Use:

```text
effectiveR = min(topic.replicationFactor, activeServerCount)
```

Every server must independently compute the same responsible set.

## Event storage key

Reuse the Phase 0.3 publisher sequence model.

For an event:

```text
eventKey =
    SHA-256(
        topicId
        || publisherKeyId
        || sequenceNumber
    )
```

Use deterministic binary encoding:

```text
topicId          = 32 bytes
publisherKeyId   = 32 bytes
sequenceNumber   = unsigned 64-bit big-endian
```

`eventKey` is distinct from the Phase 0.3 `eventId`.

The `eventId` authenticates event contents.

The `eventKey` determines persistence placement and lookup.

## Stored event record

Store:

```text
StoredEvent
  eventKey
  eventEnvelope
  storedAt
  topicRetentionPeriod
  storedAtEpoch
  expiresAfterEpoch
```

The original signed `EventEnvelope` must be stored unchanged.

Before storing an event, verify:

- event ID;
- Ed25519 signature;
- topic exists and is active;
- publisher authorization.

A replication server must not persist an invalid or unauthorized event.

## Retention

D2 defines retention per topic in epochs.

For an event first accepted for persistence:

```text
expiresAfterEpoch =
    storedAtEpoch + topic.retentionPeriod
```

Replication servers shall periodically remove expired event records.

Retention tests may use an injectable clock/current-epoch provider so unit and integration tests do not need to wait for real Cardano epochs.

## Store flow

When a Pub/Sub node first accepts a newly published event:

```text
EventEnvelope
     ↓
choose any active replication server
     ↓
STORE request
     ↓
entry server computes eventKey
     ↓
entry server computes responsible R servers
     ↓
store on all responsible servers
     ↓
acknowledge after required replicas confirm
```

The Pub/Sub node does not need to know which individual servers are responsible for the key.

Persistence failure must not invalidate live dissemination, but it must be logged.

## Replication RPC

Provide a versioned replication-server API.

At minimum:

```text
POST /v1/events
GET  /v1/events/{eventKey}
GET  /v1/topics/{topicId}/publishers
GET  /v1/health
```

Internal server-to-server replication may use additional endpoints.

Use bounded:

- connection timeouts;
- request timeouts;
- payload sizes;
- retries.

## Lookup flow

A client may contact any active replication server with an `eventKey`.

The contacted server shall:

1. compute the responsible replica set;
2. query one responsible server;
3. return the stored event;
4. try another responsible server if the first is unavailable.

The returned event must be revalidated by the requesting Pub/Sub node before local delivery.

## Per-topic publisher progress log

Implement the lightweight D2 topic log.

For each topic, maintain:

```text
PublisherProgress
  publisherKeyId
  latestSequenceNumber
  latestTimestamp
```

The topic-log key is:

```text
topicLogKey = SHA-256(topicId)
```

Whenever an event is successfully persisted, update the publisher's topic-log entry if its sequence number is newer.

Replicate the topic log using the same effective replication factor `R`.

## Missed-event state

Each Pub/Sub node shall persist, per subscribed topic and publisher:

```text
lastDeliveredSequenceNumber
lastDeliveredTimestamp
```

Update this state after an event is locally accepted/delivered.

It must survive node restart.

## Offline recovery

Add a recovery operation to the Pub/Sub node:

```text
POST /v1/events/recover/{topicId}
```

Recovery flow:

```text
subscriber reconnects
        ↓
query topic publisher-progress log
        ↓
compare remote latest sequence
with local last-delivered sequence
        ↓
construct missing eventKeys
        ↓
fetch missing events
        ↓
validate each EventEnvelope
        ↓
deliver in per-publisher sequence order
        ↓
update local recovery state
```

Requests for independent missing keys should execute concurrently with a configurable bound.

An already delivered event must not be delivered twice.

## Integration with Phase 0.6

Live dissemination remains unchanged:

```text
publisher
   ↓
Hybrid Dissemination
   ↓
online subscribers
```

Persistence is a parallel path:

```text
publisher / accepting node
   ↓
Replication DHT
   ↓
retained replicas
```

Phase 0.7 must not route live events through the DHT.

## Storage implementation

For the prototype, each replication server may use a persistent local filesystem store.

Store at least:

```text
events/
topic-logs/
metadata/
```

Writes shall be atomic.

Restarting a replication server must preserve stored events and topic logs.

## Logging

Add structured events:

```text
REPLICATION_SERVER_STARTED
REPLICATION_REGISTRY_SYNCED
EVENT_PERSIST_REQUESTED
EVENT_REPLICA_STORED
EVENT_PERSISTED
EVENT_PERSIST_FAILED
EVENT_LOOKUP_REQUESTED
EVENT_LOOKUP_HIT
EVENT_LOOKUP_MISS
TOPIC_LOG_UPDATED
RECOVERY_STARTED
RECOVERY_EVENT_FOUND
RECOVERY_EVENT_DELIVERED
RECOVERY_COMPLETED
EVENT_EXPIRED
```

Include where applicable:

```text
eventKey
eventId
topicId
publisherKeyId
sequenceNumber
serverId
replicaCount
```

## Tests

### Replication registry

Verify:

- valid server registration;
- controlling signer authorization;
- active/inactive filtering;
- deterministic `serverId`;
- registry reconstruction after restart.

### DHT assignment

Verify:

- deterministic responsible-set computation;
- replication factor `R`;
- `R > activeServerCount`;
- 256-bit wraparound;
- identical results on all servers.

### Event keys

Verify:

- deterministic key encoding;
- different topic changes key;
- different publisher changes key;
- different sequence changes key.

### Storage

Verify:

- valid event storage;
- invalid signature rejection;
- unauthorized publisher rejection;
- restart persistence;
- expiration cleanup.

### Topic log

Verify:

- first publisher entry;
- sequence advancement;
- older sequence does not move progress backward;
- multiple publishers are independent.

### Recovery

Verify:

- no missed events;
- one missed event;
- multiple missed events;
- multiple publishers;
- duplicate suppression;
- restart preserves last-delivered state.

## Acceptance

Create:

```bash
./scripts/acceptance/phase-0.7.sh
```

Acceptance shall:

1. Start a fresh Cardano devnet.
2. Deploy the existing Topic Registry.
3. Deploy the Replication Server Registry.
4. Start three replication servers.
5. Register all three replication servers on-chain.
6. Verify all servers observe the same active membership.
7. Start the three Pub/Sub nodes with SecureCyclon, Navigation, and Dissemination.
8. Create an open topic with replication factor `R = 2`.
9. Subscribe all three Pub/Sub nodes.
10. Publish event sequence `0`.
11. Verify live dissemination reaches every subscriber.
12. Verify the event is persisted on exactly the deterministic responsible replica set.
13. Verify lookup by `eventKey` succeeds through a non-responsible entry server.
14. Stop `node-3`.
15. Publish at least three additional events while `node-3` is offline.
16. Verify those events are persisted and the topic publisher log advances.
17. Restart `node-3` with its existing identity and subscription state.
18. Trigger recovery on `node-3`.
19. Verify it identifies and retrieves exactly the missed sequence range.
20. Verify recovered events pass Phase 0.3 validation before delivery.
21. Verify each missed event is delivered exactly once and in publisher sequence order.
22. Restart one replication server and verify its persisted data survives.
23. Verify SecureCyclon, Navigation, Hybrid Dissemination, registry sync, and PING/PONG remain operational.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.7/
```

Include:

- replication-registry deployment;
- three on-chain server registrations;
- active replication-server membership;
- server IDs;
- event-key calculations;
- responsible replica-set calculations;
- stored replica inventories;
- successful lookup evidence;
- topic publisher log before/after offline period;
- node-3 pre-offline delivery state;
- events published during the offline period;
- node-3 recovery request/result;
- recovered event logs;
- replication-server restart persistence evidence;
- final acceptance result.

## Completion criterion

Phase 0.7 is complete when:

```bash
./scripts/acceptance/phase-0.7.sh
```

passes from a clean environment and a subscriber that was offline can recover exactly the signed events it missed from the replicated persistence layer.
