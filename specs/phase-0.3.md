# Phase 0.3 — Signed Event Model and Baseline Dissemination

## Goal

Add the event format used by the Pub/Sub network, sign events with persistent node identities, validate publishers against the Cardano Topic Registry, maintain publisher sequence numbers, suppress duplicates, and deliver events across the existing three-node transport.

## Identity decision

Reuse the persistent Ed25519 node identity introduced in Phase 0.1 for event signing.

For publishers:

```text
publisherKeyId = SHA-256(encoded Ed25519 public key)
```

The topic registry `publishers[]` field shall store `publisherKeyId` values.

Owners and administrators continue to use Cardano transaction identities for on-chain administration.

`scripts/registry/add-publisher.sh` shall support registering a Pub/Sub node's event-signing key ID.

## Modules

Add:

```text
libs/event-core/
scripts/events/
scripts/acceptance/phase-0.3.sh
implementation-reports/evidence/phase-0.3/
```

Update:

```text
libs/protocol-core/
libs/transport-netty/
apps/pubsub-node/
libs/registry-api/
libs/registry-cardano/
```

## Event envelope

Implement an immutable event model:

```text
EventEnvelope
  protocolVersion
  topicId
  publisherPublicKey
  sequenceNumber
  timestamp
  payload
  signature
  eventId
```

Requirements:

- `topicId` is the 256-bit ID from the Cardano registry.
- `publisherPublicKey` is the encoded Ed25519 public key of the publisher.
- `sequenceNumber >= 0`.
- `timestamp` is UTC epoch milliseconds.
- `payload` is arbitrary bytes.
- `signature` is Ed25519.
- `eventId` is 256 bits.

## Canonical event body

Do not sign JSON directly.

Define a deterministic binary representation containing, in order:

```text
protocolVersion
topicId
publisherPublicKey
sequenceNumber
timestamp
payloadLength
payload
```

Use fixed-width integer encoding and length-prefix variable fields.

Compute:

```text
signature = Ed25519.sign(canonicalEventBody)
eventId   = SHA-256(canonicalEventBody)
```

Receivers must recompute `eventId` and verify the signature before accepting an event.

## Publisher sequence numbers

Maintain one sequence counter per:

```text
(topicId, publisherKeyId)
```

The first event uses:

```text
sequenceNumber = 0
```

Each subsequent event increments by one.

Store publisher event state under the node's persistent runtime volume.

Persist produced events before network transmission so a node restart does not reuse a sequence number or silently lose a reserved event.

On restart, the publisher shall continue from the next sequence number.

## Registry validation

Before an event is accepted, the receiving node shall obtain the current `TopicState`.

Validation order:

1. Topic exists.
2. Topic is active.
3. `eventId` matches the canonical body.
4. Ed25519 signature is valid.
5. Compute `publisherKeyId`.
6. If `publishers[]` is empty, accept any valid signer.
7. If `publishers[]` is non-empty, require `publisherKeyId` in the list.
8. Check duplicate/sequence state.
9. Deliver the event locally.

A deleted or unknown topic must reject new events.

## Duplicate and sequence handling

Maintain a bounded cache of recently accepted events.

For the same:

```text
(topicId, publisherKeyId, sequenceNumber)
```

- same `eventId` → duplicate; do not deliver twice;
- different `eventId` → sequence conflict; reject.

Event arrival order must not be assumed to be monotonic.

## Wire protocol

Add an `EVENT` message type to the existing framed protocol.

The message shall carry the complete `EventEnvelope`.

The transport must enforce the existing maximum frame size.

## Baseline dissemination

Use the current Phase 0.1 peer connections as the initial dissemination topology.

When a locally published event is valid:

```text
publisher
   ↓
all connected peers
```

When a node first accepts an event received from a peer, it may forward it to its other connected peers.

Duplicate suppression must prevent repeated local delivery and forwarding loops.

Keep event validation independent from neighbor-selection logic so later dissemination phases can replace the baseline peer selection.

## Node control API

Add a minimal local control endpoint to `pubsub-node`.

Default binding:

```text
127.0.0.1
```

Required operation:

```text
POST /v1/events/publish
```

Request:

```json
{
  "topicId": "<hex>",
  "payload": "<base64>"
}
```

Response shall contain at least:

```json
{
  "eventId": "<hex>",
  "sequenceNumber": 0
}
```

Add:

```text
scripts/events/publish.sh
```

to invoke the endpoint for a selected node.

Provide an acceptance-only mechanism for constructing an unauthorized signed event and a tampered-signature event so receiver-side validation can be tested without relying only on publisher preflight checks.

## Logging

Add structured events:

```text
EVENT_PUBLISHED
EVENT_RECEIVED
EVENT_ACCEPTED
EVENT_FORWARDED
EVENT_DUPLICATE
EVENT_REJECTED
EVENT_SEQUENCE_CONFLICT
```

`EVENT_REJECTED` shall include a reason such as:

```text
UNKNOWN_TOPIC
INACTIVE_TOPIC
INVALID_EVENT_ID
INVALID_SIGNATURE
UNAUTHORIZED_PUBLISHER
INVALID_SEQUENCE
```

Include:

```text
eventId
topicId
publisherKeyId
sequenceNumber
peerNodeId
```

where applicable.

## Tests

### Event-core tests

Verify:

- canonical encoding is deterministic;
- signing and verification;
- modified payload invalidates the signature;
- modified metadata invalidates the signature;
- `eventId` is deterministic;
- publisher key ID derivation;
- per-topic/per-publisher sequence counters;
- sequence state survives restart;
- duplicate detection;
- same sequence with different event ID is rejected.

### Registry integration tests

Verify:

- open topic accepts any valid event signer;
- moderated topic accepts a registered publisher;
- moderated topic rejects an unregistered publisher;
- deleted topic rejects events.

### Transport tests

Verify:

- `EVENT` encode/decode;
- event transmission between nodes;
- invalid events are not delivered;
- duplicate events are delivered once;
- forwarding does not create an infinite loop.

## Acceptance

Create:

```bash
./scripts/acceptance/phase-0.3.sh
```

The acceptance flow shall:

1. Start the Phase 0.1 Cardano devnet.
2. Build/deploy the Phase 0.2 Topic Registry.
3. Start all three Pub/Sub nodes.
4. Create a moderated topic.
5. Register `node-1`'s event-signing key as publisher.
6. Publish event sequence `0` from `node-1`.
7. Verify `node-2` and `node-3` accept the same `eventId` exactly once.
8. Publish another event from `node-1` and verify sequence `1`.
9. Send a validly signed event from unregistered `node-2` and verify rejection.
10. Send a tampered-signature event and verify rejection.
11. Re-send an accepted event and verify duplicate suppression.
12. Create an open topic.
13. Publish from `node-2` and verify all nodes accept it.
14. Delete the open topic.
15. Attempt another event on the deleted topic and verify rejection.
16. Restart `node-1`.
17. Publish again on the moderated topic and verify its next sequence number is preserved.
18. Verify all three nodes remain connected and continue PING/PONG operation.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.3/
```

Include:

- topic registry setup;
- registered publisher key ID;
- published event envelopes;
- accepted-event logs from all three nodes;
- unauthorized publisher rejection;
- invalid-signature rejection;
- duplicate suppression evidence;
- open-topic publication;
- deleted-topic rejection;
- sequence continuity after restart;
- final acceptance result.

## Completion criterion

Phase 0.3 is complete when:

```bash
./scripts/acceptance/phase-0.3.sh
```

passes from a clean environment and the three-node runtime can authenticate, authorize, deduplicate, and deliver signed Pub/Sub events using Cardano registry state.
