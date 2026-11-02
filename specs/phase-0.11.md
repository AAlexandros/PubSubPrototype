# Phase 0.11 — Durable Subscriber Event Store and Automatic Catch-Up

## 1. Goal

Phase 0.11 closes the subscriber-delivery gap left by the current persistence and recovery path.

Today, replication servers durably store full `EventEnvelope` objects and Pub/Sub nodes maintain durable delivery progress, but a receiving node does not durably retain the event payloads it accepts. Recovery is also initiated explicitly through a recovery command/API. This means a node can record that an event was recovered without providing a durable local event history to the user.

After Phase 0.11, every Pub/Sub node must:

1. durably persist every event it accepts for a subscribed topic, including the complete event envelope and payload;
2. restore that local event store after node/process/container restart;
3. automatically catch up from the replication layer after restart, without a user issuing an explicit recovery command;
4. expose read-only APIs and user-script commands for retrieving locally stored events;
5. advance durable delivery progress only after the corresponding event has been successfully stored locally.

The node-local event store becomes the subscriber-facing source of truth for event consumption. Replication servers remain the authoritative recovery source for events that are missing locally.

---

## 2. Relationship to Existing Phases

Phase 0.11 builds on the existing behavior from Phases 0.3, 0.5, 0.6, 0.7, 0.8, and 0.9.

The following behavior must remain unchanged unless explicitly overridden below:

- Events continue to use the existing signed `EventEnvelope`, deterministic event ID, publisher key ID, and publisher sequence number.
- Event validation remains unchanged: topic validity, event ID, signature, publisher authorization, duplicate detection, and sequence rules still apply before an event is accepted.
- Hybrid Dissemination remains the live delivery mechanism.
- Replication servers remain responsible for replicated persistence, retention, lookup, publisher progress, failure repair, and replication-factor maintenance.
- The existing durable per-topic/per-publisher delivery progress remains the basis for determining which events are missing.
- Recovery does not inject recovered events back into the dissemination overlay.
- Existing replication-server retention semantics are not changed by this phase.

Phase 0.11 changes the node-side meaning of delivery: a validated event is not considered durably delivered until the complete event has been committed to the node-local event store.

---

## 3. Node-Local Event Store

### 3.1 Scope

Each Pub/Sub node must have its own persistent local event store.

The store contains the full `EventEnvelope` for every event accepted by that node for a topic to which the node is subscribed. This applies equally to events arriving through:

- live Hybrid Dissemination;
- automatic catch-up from replication servers;
- local publication when the publishing node is itself subscribed to the topic.

A node must not store an event merely because it forwarded, observed, or persisted the event on behalf of the replication layer. The local event store represents events delivered to that node as a subscriber.

### 3.2 Persistence format

The implementation should follow the repository's existing filesystem-persistence conventions: atomic writes, deterministic paths, restart reconstruction, and JSON-encoded state where appropriate.

A recommended layout is:

```text
<node-runtime>/events/
  <topicId>/
    <publisherKeyId>/
      <sequenceNumber>.json
```

Each JSON file must contain the complete event envelope required to reconstruct the event without contacting another component, including at minimum:

- `eventId`
- `topicId`
- `publisherPublicKey`
- `publisherKeyId` if represented explicitly by the current event model/API
- `sequenceNumber`
- publication timestamp
- payload in the existing serialized representation
- signature

If the current `EventEnvelope` JSON representation already contains all necessary fields, it should be stored directly rather than introducing a second event schema.

The exact directory layout may differ if the implementation can demonstrate equivalent deterministic lookup, atomicity, and restart behavior.

### 3.3 Atomicity and crash safety

Writing an event must be atomic from the perspective of the node.

A partially written file must never be interpreted as a valid stored event after restart. The implementation should use the same write-to-temporary-file and atomic-rename pattern already used elsewhere in the prototype where supported by the filesystem.

The following ordering is required for both live delivery and recovered delivery:

```text
validate event
    -> persist full event locally
    -> confirm local persistence
    -> advance durable delivery progress
    -> report delivery/acceptance success
```

If local persistence fails, delivery progress must not advance for that event.

This rule prevents the node from reaching a state in which its cursor claims that an event has been delivered although the payload is absent from local storage.

### 3.4 Idempotency

Local event persistence must be idempotent.

Persisting the same valid event multiple times must not create duplicate logical events or corrupt the existing event.

If an event already exists at the expected `(topicId, publisherKeyId, sequenceNumber)` location:

- the same `eventId` and envelope must be treated as an idempotent duplicate;
- conflicting content for the same publisher sequence must be rejected using the existing sequence-conflict semantics and must not overwrite the stored event.

### 3.5 Restart reconstruction

When a node starts, it must reconstruct or validate any in-memory indexes from the persisted event files.

A node restart must not require rebuilding the store from replication servers when the corresponding event files are already present locally.

The node must tolerate the following restart cases:

- local event store and delivery progress are both current;
- event file exists but delivery progress was not advanced before a crash;
- delivery progress is behind several already persisted contiguous events;
- a malformed/corrupt local event file is encountered.

Where an event file is valid and already persisted, restart reconciliation may use it to safely advance stale delivery progress. It must never advance progress past a missing or invalid sequence.

Corrupt local files must be reported explicitly and must not be silently treated as delivered.

### 3.6 Local retention

Phase 0.11 does not introduce automatic deletion of subscriber-local event files.

Once an accepted event is stored locally, it remains in the node's local event store across restart unless an operator explicitly removes/reset the node data using existing testbed reset mechanisms or a future retention/pruning feature is introduced.

This does not change the topic retention period applied by replication servers. Automatic catch-up can only recover events that are still available from the replication layer.

---

## 4. Automatic Catch-Up

### 4.1 Removal of user-triggered recovery from the normal workflow

Users must no longer be required to run a command such as:

```text
pubsub-node recover --node <n> --topic <topicId>
```

to restore events missed while a node was offline.

The existing explicit recovery endpoint/command may be removed from the public user workflow or retained only as an internal/diagnostic compatibility path, but Phase 0.11 acceptance must not depend on it.

Normal subscriber recovery must be automatic.

### 4.2 Startup trigger

After a node starts and has loaded:

- its persistent identity;
- its persisted subscriptions;
- its local event store/delivery progress;
- the current topic-registry view;
- the current active replication-server membership required for lookup;

it must automatically start catch-up for every persisted active subscription.

Catch-up must not require a user API call.

The startup sequence should conceptually be:

```text
node starts
  -> restore identity
  -> restore subscriptions
  -> restore local events and delivery progress
  -> obtain registry + replication membership readiness
  -> for each active subscribed topic:
       compare local progress with replication publisher progress
       fetch missing events
       validate
       persist locally
       advance delivery progress
  -> continue normal live operation
```

The node does not need to block all networking while catch-up runs. Live dissemination may continue concurrently, provided duplicate suppression, local persistence, and cursor advancement remain correct and race-safe.

### 4.3 Catch-up algorithm

Phase 0.11 should reuse the existing Phase 0.7 recovery machinery rather than creating an independent recovery protocol.

For each subscribed topic, the node should:

1. determine local durable progress per publisher;
2. query replication-server publisher progress using the existing topic-publisher API;
3. determine missing sequence numbers that should still be recoverable;
4. fetch missing events using existing replication lookup behavior with bounded concurrency and existing timeout/failover rules;
5. revalidate every fetched `EventEnvelope` using the normal node event validation rules;
6. persist each valid event to the local event store;
7. advance progress only for contiguous successfully persisted sequences;
8. suppress events that are already stored/delivered;
9. leave a gap unresolved if a required event is unavailable or invalid rather than skipping over it.

The implementation should preserve the existing optimization that queries only publisher progress newer than the node's durable topic delivery timestamp where applicable.

### 4.4 Interaction with live events

Automatic catch-up and live dissemination may race for the same event.

The outcome must still be exactly one logical local event.

Whichever path persists the event first wins the idempotent local-store operation. The other path must recognize the duplicate and must not create a second local record or advance the cursor incorrectly.

### 4.5 Unavailable historical events

A node may have been offline for longer than the replication-server retention period.

If replication publisher progress indicates a sequence range that can no longer be retrieved, the node must not fabricate progress or silently skip the missing event.

The catch-up result/status must expose that the topic/publisher contains an unrecoverable gap, including enough information to identify the missing sequence(s) or first missing sequence.

Later events for the same publisher must not cause the durable contiguous cursor to jump over that gap.

This phase does not define an application-level policy for accepting permanent history gaps.

### 4.6 Catch-up after newly creating a subscription

A newly created subscription does not automatically imply replay of the topic's entire retained history.

Phase 0.11 automatic catch-up is required for persisted subscriptions restored after restart/offline operation. Existing subscribe semantics remain unchanged unless the current implementation already establishes a recovery baseline for new subscriptions.

The implementation may expose future historical-subscription behavior separately, but it is not required by this phase.

---

## 5. Local Event Query API

The node must expose read-only access to events from its own local persistent store. User-facing queries must not query replication servers directly.

### 5.1 Get one event

Provide an endpoint equivalent to:

```http
GET /v1/events/{eventId}
```

The response must return the complete locally stored `EventEnvelope`, including the payload.

If the event is not in the local node store, return `404` even if the event might still exist on a replication server.

If event ID lookup would otherwise require scanning every file, the implementation should maintain/rebuild a local index or provide an equivalent deterministic lookup mechanism.

### 5.2 Get one publisher sequence

Provide an endpoint equivalent to:

```http
GET /v1/events?topicId=<topicId>&publisherKeyId=<publisherKeyId>&sequenceNumber=<n>
```

or an equivalent path-based endpoint.

This lookup should map directly to the deterministic local store key.

### 5.3 List stored events

Provide an endpoint equivalent to:

```http
GET /v1/events
```

which lists locally persisted events.

The API must support at least filtering by:

- `topicId`
- `publisherKeyId`

and deterministic ordering by a documented key. Recommended default ordering is:

```text
topicId, publisherKeyId, sequenceNumber
```

Because experiment runs may contain many events, the API must be bounded. Pagination or an explicit configurable result limit is required; the endpoint must not construct an unbounded in-memory response.

The response may contain full envelopes or event summaries. If summaries are used for list responses, each item must contain enough identity fields to retrieve the complete event through the single-event endpoint.

### 5.4 Topic-local history

The following convenience endpoint is recommended:

```http
GET /v1/topics/{topicId}/events
```

It should expose the same locally persisted data, filtering only by topic.

### 5.5 Catch-up status

Expose read-only operational status, either by extending the existing recovery-state endpoint or adding an endpoint equivalent to:

```http
GET /v1/events/catch-up/status
```

The status should report at least:

- whether initial automatic catch-up has started/completed;
- per-topic state;
- number of events fetched and persisted in the latest run;
- already-present/duplicate count;
- unavailable/missing count;
- validation/persistence failure count;
- first unresolved gap per publisher when one exists;
- timestamps for the latest catch-up attempt/completion.

This endpoint is observational. It must not be required to trigger recovery.

---

## 6. User Script / CLI

Update `scripts/pubsub-node.mjs` or the current equivalent node-control script so that users can inspect the local store without directly contacting replication servers.

Required commands should include equivalents of:

```text
node scripts/pubsub-node.mjs events --node 3
node scripts/pubsub-node.mjs events --node 3 --topic <topic-id>
node scripts/pubsub-node.mjs event --node 3 --event <event-id>
```

A publisher/sequence lookup should also be supported, for example:

```text
node scripts/pubsub-node.mjs event --node 3 \
  --topic <topic-id> \
  --publisher <publisher-key-id> \
  --sequence 5
```

The exact command names may follow the current script's conventions.

The important invariant is:

> User event reads are served by the Pub/Sub node's local persistent event store. The user script must not perform recovery or query a replication server as a fallback.

The normal user-facing `recover` command should be removed or clearly marked diagnostic/deprecated so that it is not part of the standard workflow.

---

## 7. Service Boundaries and Suggested Implementation Shape

The implementation should keep persistence concerns separated from event validation, dissemination, and replication-server storage.

A suggested shape is:

```text
EventProcessingService
        |
        | accepted EventEnvelope
        v
NodeEventStore / LocalEventStore
        |
        +--> atomic event JSON persistence
        +--> local indexes / listing
        |
        v
DeliveryStateStore

StartupCatchUpService
        |
        +--> ReplicationClient / existing RecoveryService
        +--> EventProcessing validation callback
        +--> NodeEventStore
        +--> DeliveryStateStore
```

The exact class names are not mandatory.

However, Phase 0.11 should avoid coupling HTTP handlers directly to filesystem operations. The node HTTP adapter should call a service abstraction for local event reads and catch-up status.

### 7.1 One local persistence path

Live events and recovered events should converge on the same durable-local-delivery operation.

There should not be separate filesystem formats or inconsistent cursor behavior for live versus recovered events.

A preferred conceptual method is:

```text
acceptAndStore(EventEnvelope event, DeliverySource source)
```

where validation may happen before or within the service according to the current architecture, but persistence and progress advancement follow the same invariant for both sources.

---

## 8. Structured Logging and Telemetry

Add structured lifecycle events sufficient to observe the new behavior and integrate it with the existing Phase 0.9 telemetry pipeline.

At minimum, provide equivalents of:

- `LOCAL_EVENT_STORED`
- `LOCAL_EVENT_STORE_DUPLICATE`
- `LOCAL_EVENT_STORE_FAILED`
- `LOCAL_EVENT_STORE_LOADED`
- `CATCH_UP_STARTED`
- `CATCH_UP_TOPIC_STARTED`
- `CATCH_UP_EVENT_FETCHED`
- `CATCH_UP_EVENT_STORED`
- `CATCH_UP_GAP`
- `CATCH_UP_TOPIC_COMPLETED`
- `CATCH_UP_COMPLETED`
- `CATCH_UP_FAILED`

Relevant logs should include stable identifiers where applicable:

- node ID
- topic ID
- event ID
- publisher key ID
- sequence number
- source (`LIVE`, `CATCH_UP`, optionally `LOCAL_PUBLISH`)
- replication entry server used for recovery when applicable
- error/reason code
- duration/count fields for catch-up summaries

Phase 0.9 telemetry ingestion should continue to work. Extend the existing event/persistence datasets rather than creating redundant datasets unless the current typed catalog requires a clean new dataset.

The experiment framework must be able to distinguish live delivery from automatic catch-up delivery.

---

## 9. Configuration

Add only the configuration needed for robust operation. Reasonable settings include:

```yaml
localEventStore:
  path: /data/events

catchUp:
  enabled: true
  fetchConcurrency: <existing recovery default>
  requestTimeoutMs: <existing recovery default>
```

Where possible, reuse Phase 0.7 persistence/recovery timeout and concurrency settings rather than defining duplicate knobs.

`catchUp.enabled` may be configurable for tests, but it must default to `true` in the Phase 0.11 runtime/testbed configuration.

The event-store path must reside on the node's persistent volume in Docker/testbed deployments.

---

## 10. Correctness Invariants

Phase 0.11 must preserve the following invariants.

1. **Durable-before-delivered**  
   Delivery progress never advances past an event that has not been successfully persisted in the local node store.

2. **Complete envelope locally available**  
   A locally delivered event can be retrieved with its payload without contacting a peer or replication server.

3. **Restart persistence**  
   Restarting the Pub/Sub process/container preserves all previously stored local events.

4. **Automatic restart catch-up**  
   A subscribed node that misses events while offline automatically attempts to recover them after restart.

5. **No user-trigger requirement**  
   Successful catch-up does not depend on calling the explicit recovery endpoint/CLI.

6. **No direct user reads from replication servers**  
   User event queries are answered from the node-local store only.

7. **Gap safety**  
   Missing/invalid/unavailable sequence `n` prevents the contiguous cursor from advancing to `n+1` for the same publisher.

8. **Idempotent convergence**  
   The same event arriving through live dissemination and catch-up results in exactly one logical locally stored event.

9. **Validation preserved**  
   Automatically fetched events pass the same authenticity/topic/publisher validation required for live events.

10. **Isolation by subscription**  
    The subscriber-local store contains delivered events for the node's subscriptions, not arbitrary replication-server contents.

---

## 11. Tests

### 11.1 Unit tests

Add tests for at least:

- atomic store and reload of a complete `EventEnvelope` including payload;
- deterministic lookup by topic/publisher/sequence;
- lookup by event ID;
- idempotent repeated writes;
- rejection of conflicting content for an occupied publisher sequence;
- list filtering and deterministic ordering;
- pagination/result bounding;
- corrupt/partial file handling;
- restart reconstruction;
- reconciliation when event file exists but cursor lags;
- cursor not advancing when local event persistence fails;
- live/catch-up race for the same event;
- contiguous progress stopping at a missing sequence;
- automatic catch-up service scheduling for persisted subscriptions;
- no catch-up for unsubscribed topics.

### 11.2 Integration tests

Add node/replication integration coverage for:

1. start a subscribed node;
2. receive a live event;
3. verify the event and payload through the node-local query API;
4. restart the node;
5. verify the same event remains queryable without recovery;
6. stop the node;
7. publish multiple events while it is offline;
8. restart the node;
9. do **not** invoke any recover command;
10. wait for automatic catch-up completion;
11. verify all missed events are now locally queryable in sequence;
12. verify persisted local files survive another restart.

Also test a race where one missed event arrives through live dissemination shortly after restart while catch-up is fetching it, and verify exactly one local copy.

---

## 12. Phase 0.11 Acceptance Scenario

Add:

```text
scripts/acceptance/phase-0.11.sh
scripts/acceptance/phase-0.11.mjs
```

or equivalent entrypoints following the current repository convention.

The acceptance flow must use the integrated local testbed and real components, not mocks.

### 12.1 Setup

1. Build/test the repository.
2. Start the existing local Cardano devnet.
3. Deploy/synchronize the Topic Registry and Replication Registry.
4. Start at least three Pub/Sub nodes and the configured replication servers with persistent node volumes.
5. Create one active topic with replication configured.
6. Subscribe node 1, node 2, and node 3.

### 12.2 Live local persistence

1. Publish event `E0` while all nodes are online.
2. Verify node 3 accepts `E0` through normal dissemination.
3. Query node 3's local event API and verify:
   - `E0` exists;
   - the complete event envelope is returned;
   - the payload matches the published payload.
4. Verify a corresponding event file exists in node 3's persistent volume.

### 12.3 Restart persistence

1. Restart node 3 without deleting its volume.
2. Before publishing anything else, query node 3 for `E0`.
3. Verify `E0` and its payload are still present.

No replication-server event query should be needed to satisfy this read.

### 12.4 Automatic offline catch-up

1. Stop node 3 while preserving its volume.
2. Publish at least three events `E1`, `E2`, and `E3` while node 3 is offline.
3. Confirm they are persisted by the replication layer.
4. Restart node 3.
5. Do **not** invoke the explicit recovery endpoint/CLI.
6. Wait for node 3's automatic catch-up to report completion.
7. Query node 3's local store and verify `E0`, `E1`, `E2`, and `E3` are all present exactly once.
8. Verify payload equality for all events.
9. Verify node 3's durable per-publisher progress is contiguous through `E3`.

### 12.5 Full-list and filtered queries

Verify through the user script and HTTP API that:

- listing all node-3 events returns the four expected local events;
- filtering by topic returns those events;
- lookup by event ID returns the correct event;
- lookup by publisher/sequence returns the correct event;
- a nonexistent local event returns `404`/not found without falling back to replication storage.

### 12.6 Second restart

1. Restart node 3 again with no new publications.
2. Verify all four events remain locally available.
3. Verify catch-up performs no duplicate logical deliveries and does not create duplicate files.

### 12.7 Gap behavior

Acceptance should include at least one focused automated test, or an integration fixture if practical, proving that an unavailable sequence does not allow the durable publisher cursor to advance past the gap.

It is not necessary for the main end-to-end acceptance run to wait for real retention expiration if that would make the test impractically long; retention-gap behavior may be covered by a deterministic integration test with controlled storage state/time.

---

## 13. Acceptance Evidence

Capture evidence under:

```text
implementation-reports/evidence/phase-0.11/
```

At minimum include:

- acceptance output;
- final result marker;
- topic ID and subscription evidence;
- published event envelopes or event IDs for `E0`–`E3`;
- node-3 local-event listing before first restart;
- node-3 local-event listing after first restart;
- node-3 automatic catch-up status/logs after offline restart;
- node-3 local-event listing after catch-up;
- individual event lookup responses showing payloads;
- persisted node-3 event-store inventory or safe path/file-name listing;
- delivery/cursor state after catch-up;
- node-3 listing after second restart;
- structured logs containing the Phase 0.11 local-store and catch-up events;
- evidence that no explicit recover command was invoked during the automatic-catch-up portion.

Do not copy private signing keys or other secret material into evidence.

---

## 14. Documentation

Update the relevant project documentation to explain the new subscriber model:

- live and recovered events are durably stored on each subscribed node;
- the node automatically catches up after restart;
- replication servers are recovery infrastructure, not the normal user-facing event-read API;
- users read their event history from their node's local event API/script;
- local event storage currently has no automatic Phase 0.11 pruning policy;
- replication-server topic retention still bounds how far an offline node can automatically recover missing history.

Update the script reference with the new `events`/`event` commands and deprecate/remove the normal `recover` user workflow.

---

## 15. Out of Scope

Phase 0.11 does not require:

- changing Hybrid Dissemination topology or forwarding rules;
- changing event signature/event-ID formats;
- changing replication-server DHT placement;
- changing replication-server retention semantics;
- disseminating recovered events back to peers;
- automatic node-local event pruning;
- an application acknowledgement protocol beyond successful durable local storage;
- replaying the entire retained history when a user creates a brand-new subscription;
- cross-node queries over subscriber-local stores;
- replacing the replication servers with node-local storage.

---

## 16. Definition of Done

Phase 0.11 is complete when all of the following are true:

- every subscriber node durably stores full accepted event envelopes and payloads;
- the local store survives process/container restart through the persistent node volume;
- live and recovered events share the same durable local-delivery semantics;
- durable delivery progress advances only after successful local event persistence;
- restart automatically triggers catch-up for persisted active subscriptions;
- catch-up uses the existing replication layer and validation rules;
- no user recovery command is required in the normal workflow;
- users can list and retrieve local events through the node API and script;
- user queries do not fall back to replication-server reads;
- duplicate live/recovery races converge to one local event;
- unresolved gaps do not get skipped;
- unit/integration tests cover storage, restart, automatic catch-up, idempotency, and gap safety;
- the Phase 0.11 end-to-end acceptance passes with evidence captured under the repository's standard evidence directory.
