# Phase 0.6 — Hybrid Dissemination Layer

## Goal

Implement the D2 Dissemination Layer on top of Phase 0.5 Navigation.

For every subscribed topic, each node shall maintain:

- one predecessor ring link;
- one successor ring link;
- one or more random same-topic links.

Events shall be forwarded through this per-topic dissemination view instead of being broadcast to all active transport sessions.

## D2 model

The layer combines:

```text
ring links
    → reliability

random same-topic links
    → dissemination speed
```

The ring is ordered by persistent `NodeId`.

Navigation supplies fresh same-topic candidates.

Phase 0.6 must not depend on global knowledge of all topics. It only requires:

```text
topicId
local subscription state
same-topic candidates from Navigation
```

## Architecture

Add:

```text
libs/dissemination/
scripts/acceptance/phase-0.6.sh
implementation-reports/evidence/phase-0.6/
```

Update:

```text
libs/protocol-core/
libs/transport-netty/
apps/pubsub-node/
ops/config/
ops/infra/
```

Keep dissemination state separate from:

- SecureCyclon view;
- Navigation view;
- Cardano registry state;
- event validation/deduplication state.

## Dissemination view

Maintain one independent view per subscribed topic.

Logical structure:

```text
TopicDisseminationView
  topicId
  predecessor
  successor
  randomPeers[]
```

Requirements:

- all peers must subscribe to the same topic;
- never retain self;
- never retain duplicate node IDs;
- predecessor and successor are chosen by cyclic `NodeId` ordering;
- random peers come from Navigation's same-topic candidates;
- random link count is configurable;
- default random link count is `1`.

## Ring ordering

Treat `NodeId` as a 256-bit unsigned value in a cyclic identifier space.

For local node `p` and same-topic candidates:

```text
predecessor = closest lower NodeId modulo 2^256
successor   = closest higher NodeId modulo 2^256
```

When only one other subscriber is known, the same peer may temporarily serve as both predecessor and successor.

When no same-topic peer is known, the node remains a single-node topic overlay.

## Vicinity-based ring maintenance

Run a periodic dissemination gossip cycle.

For gossip selection, prefer the dissemination neighbor whose `NodeId` is closest to the local node.

When node `p` sends candidates to gossip partner `q`, consider:

```text
p dissemination view for topic
        +
p Navigation same-topic candidates
```

Select candidates closest to `q`'s `NodeId`:

- closest lower IDs;
- closest higher IDs;
- modulo the 256-bit identifier space.

When candidates are received, merge them with the local dissemination view and Navigation same-topic candidates, then recompute predecessor/successor.

This shall progressively converge the same-topic overlay into a ring.

## Random same-topic links

The random portion of the Hybrid Dissemination view shall be refreshed from Navigation.

Requirements:

- peers must be subscribers of the topic;
- random links must not replace predecessor/successor state;
- random links shall be periodically refreshed;
- prefer fresh Navigation candidates;
- random selection shall be reproducible in tests through configurable seeds.

## Navigation integration

Expose or use a Navigation API that can provide same-topic candidates, for example:

```java
List<NavigationPeerDescriptor> peersForTopic(TopicId topicId);
```

Phase 0.6 must not inspect Navigation implementation internals.

The expected flow is:

```text
SecureCyclon
     ↓
Navigation
     ↓
same-topic candidates
     ↓
Hybrid Dissemination
```

## Wire protocol

Add dissemination gossip messages:

```text
DISSEMINATION_REQUEST
DISSEMINATION_RESPONSE
```

They shall carry at least:

```text
topicId
sender descriptor
selected same-topic candidates
protocolVersion
```

Use the existing framed Netty transport and dynamic connection mechanism.

## Event forwarding

Replace Phase 0.3 baseline broadcast forwarding.

Before forwarding, keep all existing Phase 0.3 validation:

```text
topic active
eventId valid
signature valid
publisher authorized
duplicate/sequence checks
```

After first acceptance, forward only through the event topic's dissemination view.

### Locally published event

Send to:

```text
predecessor
successor
randomPeers[]
```

### Event received from a ring peer

Send to:

```text
other ring direction
randomPeers[]
```

Do not send it back to the peer it came from.

### Event received from a random peer

Send to:

```text
predecessor
successor
```

Do not send it back to the peer it came from.

Existing duplicate suppression remains authoritative and prevents repeated local delivery or forwarding loops.

## Subscription changes

On subscribe:

- create a dissemination view for the topic;
- seed it from current Navigation same-topic candidates;
- begin dissemination gossip.

On unsubscribe:

- remove the topic's dissemination view;
- stop dissemination gossip for that topic;
- do not forward future events for that topic as a subscriber.

Deleted topics shall remove their dissemination views.

## Failure and restart

If a dissemination peer becomes unavailable:

- remove it from the affected topic view;
- recompute predecessor/successor from remaining candidates;
- repopulate from Navigation;
- continue gossip.

Restarted nodes with the same persistent `NodeId` shall be able to re-enter the ring.

## Control API

Expose:

```text
GET /v1/dissemination/view
GET /v1/dissemination/view/{topicId}
```

Return at least:

```text
topicId
predecessor
successor
randomPeers
```

This API is for inspection and acceptance testing.

## Logging

Add structured events:

```text
DISSEMINATION_STARTED
DISSEMINATION_CYCLE
DISSEMINATION_GOSSIP_SENT
DISSEMINATION_GOSSIP_RECEIVED
DISSEMINATION_VIEW_UPDATED
DISSEMINATION_PREDECESSOR_CHANGED
DISSEMINATION_SUCCESSOR_CHANGED
DISSEMINATION_RANDOM_PEER_CHANGED
DISSEMINATION_PEER_REMOVED
EVENT_DISSEMINATED
```

Include where applicable:

```text
topicId
nodeId
peerNodeId
role
eventId
cycle
```

## Tests

### Ring selection

Verify:

- predecessor selection;
- successor selection;
- modulo wraparound;
- one-peer temporary predecessor/successor case;
- no-self and no-duplicate rules.

### Vicinity selection

Verify:

- closer lower/higher IDs beat farther IDs;
- received candidates improve ring neighbors;
- Navigation same-topic candidates are considered;
- unrelated-topic candidates are rejected.

Use deterministic fixtures:

```text
local NodeId
current dissemination view
Navigation candidates
received candidates
expected predecessor
expected successor
```

### Random links

Verify:

- only same-topic peers are selected;
- configured random-link count is respected;
- ring peers and random peers remain logically distinct;
- deterministic test seed produces reproducible selection.

### Event forwarding

Verify:

- local publication uses ring + random links;
- ring-received event uses opposite ring direction + random links;
- random-received event uses both ring directions;
- source peer is not immediately echoed;
- duplicate event is delivered once;
- event is never forwarded to nodes known not to subscribe to the topic.

### Integration

Verify:

- dynamically learned Navigation candidates can become dissemination peers;
- ring state recovers after peer failure;
- restarted peer is rediscovered;
- multiple topics maintain independent dissemination views.

## Acceptance

Create:

```bash
./scripts/acceptance/phase-0.6.sh
```

Acceptance shall:

1. Start the Cardano devnet and Topic Registry.
2. Start the three Pub/Sub nodes using SecureCyclon and Navigation.
3. Create one open topic.
4. Subscribe all three nodes to that topic.
5. Wait for Navigation to expose same-topic candidates.
6. Wait for the Dissemination Layer to converge.
7. Verify every node has valid predecessor and successor links for the topic.
8. Verify links correspond to cyclic `NodeId` ordering.
9. Verify the configured random same-topic link exists where enough peers are available.
10. Publish a signed event.
11. Verify every subscriber accepts the same `eventId` exactly once.
12. Verify event forwarding uses dissemination neighbors rather than transport-wide broadcast.
13. Stop one subscriber.
14. Verify remaining nodes repair their dissemination views.
15. Publish another event and verify delivery to all remaining subscribers.
16. Restart the stopped node with the same identity.
17. Verify it rejoins the topic ring.
18. Create a second topic and subscribe only two nodes.
19. Verify the second topic has an independent dissemination view.
20. Publish on the second topic and verify the non-subscriber does not receive the event.
21. Verify SecureCyclon, Navigation, PING/PONG, and registry synchronization remain operational.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.6/
```

Include:

- node IDs and their cyclic ordering;
- topic subscription state;
- Navigation same-topic candidates;
- initial dissemination views;
- converged dissemination views;
- predecessor/successor verification;
- random-link evidence;
- event forwarding logs;
- exact-once acceptance evidence;
- failure/repair views;
- restart/rejoin views;
- second-topic isolation evidence;
- final acceptance result.

## Completion criterion

Phase 0.6 is complete when:

```bash
./scripts/acceptance/phase-0.6.sh
```

passes and events are disseminated through per-topic Hybrid Dissemination views rather than the Phase 0.3 transport-wide broadcast path.
