# Phase 0.5 — Navigation Layer / Vicinity

## Goal

Implement the D2 Navigation Layer on top of Phase 0.4 SecureCyclon.

The layer shall use SecureCyclon peer samples and Vicinity gossip to maintain links toward:

- the node's own subscribed topics;
- finger topics at distances `b^i` clockwise;
- finger topics at distances `b^i` anticlockwise.

## D2 model

All active topics are globally known from the Cardano Topic Registry.

For the prototype, derive the topic enumeration deterministically:

```text
active topicIds
    ↓
sort lexicographically
    ↓
assign ordinals 0 .. T-1
```

Recompute the ordering whenever the active registry snapshot changes.

Default routing base:

```text
b = 2
```

Default peers retained per finger topic:

```text
c = 2
```

Both values shall be configurable.

## Topic subscriptions

Subscriptions are local node state, not on-chain state.

Add a persistent subscription store per Pub/Sub node.

Expose:

```text
POST   /v1/subscriptions/{topicId}
DELETE /v1/subscriptions/{topicId}
GET    /v1/subscriptions
```

A node may subscribe to multiple active topics.

Deleted topics shall be removed from the active subscription set.

## Navigation peer descriptor

The Navigation Layer shall operate on descriptors containing at least:

```text
nodeId
host
port
subscribedTopicIds[]
freshness
```

Do not modify the SecureCyclon view to carry Navigation state.

SecureCyclon remains the lower-layer random peer source.

## Finger topics

For a subscribed topic with ordinal `x`, compute target topic ordinals:

```text
x
x ± b^0
x ± b^1
x ± b^2
...
```

using modulo `T`.

Stop when the distance exceeds `T / 2`.

Duplicate target ordinals shall be collapsed.

For nodes subscribed to multiple topics, maintain the union of their finger-topic targets.

## Navigation view

Maintain a separate bounded Navigation view.

For each finger topic target `t`, retain up to `c` candidate peers.

Candidate ranking:

1. Prefer peers subscribed to topic `t`.
2. Otherwise prefer peers whose subscribed topic is closest to `t` in the cyclic topic ordering.
3. When candidates are equally close, prefer fresher links.
4. Never retain self.
5. Never retain duplicate node IDs for the same target slot.

The Navigation view shall not be the SecureCyclon view.

## Vicinity gossip

Run a configurable periodic Navigation gossip cycle.

Each cycle:

```text
Navigation view
    +
fresh SecureCyclon samples
        ↓
select gossip partner
        ↓
select links useful to partner's finger topics
        ↓
exchange NAVIGATION_GOSSIP
        ↓
merge local + received candidates
        ↓
run Vicinity selection function
        ↓
update Navigation view
```

When sending candidates to peer `q`, select the peers most useful for `q`'s finger topics.

When receiving candidates, recompute the local view using the local node as reference.

## SecureCyclon integration

Use `PeerSamplingService` as a source of random candidates.

The Navigation Layer must not:

- inspect SecureCyclon internals;
- alter SecureCyclon view ownership;
- depend on static full-mesh configuration.

A newly discovered SecureCyclon peer may become a Navigation candidate after its subscription information is learned.

## Wire protocol

Add Navigation-specific message types, for example:

```text
NAVIGATION_REQUEST
NAVIGATION_RESPONSE
```

Messages shall carry:

- sender node descriptor;
- sender subscriptions;
- selected navigation candidates;
- protocol version.

Use the existing framed Netty transport.

## Dynamic connections

Navigation candidates may refer to peers without an active transport session.

Reuse the Phase 0.4 dynamic connection mechanism to establish a session when needed.

## Registry changes

When the Cardano registry changes:

- recompute active topic ordering;
- recompute finger topics;
- remove deleted topics;
- re-evaluate the Navigation view.

A topic creation or deletion must not require node restart.

## Logging

Add structured events:

```text
NAVIGATION_STARTED
NAVIGATION_CYCLE
NAVIGATION_GOSSIP_SENT
NAVIGATION_GOSSIP_RECEIVED
NAVIGATION_VIEW_UPDATED
NAVIGATION_FINGER_TOPICS_UPDATED
NAVIGATION_PEER_DISCOVERED
NAVIGATION_PEER_REMOVED
```

Include where applicable:

```text
nodeId
topicId
targetTopicId
peerNodeId
distance
viewSize
cycle
```

## API

Expose a read-only endpoint:

```text
GET /v1/navigation/view
```

Return:

```text
topic ordering
local subscriptions
finger topics
selected peers per finger topic
```

This endpoint is for inspection and acceptance testing.

## Tests

### Topic ordering

Verify:

- deterministic ordering from registry state;
- correct modulo distance;
- correct finger-topic generation;
- correct behavior when topics are added or deleted.

### Vicinity selection

Verify:

- exact-topic candidates beat nearby-topic candidates;
- nearer topics beat farther topics;
- freshness breaks equal-distance ties;
- view is bounded to `c` peers per target;
- self and duplicate entries are rejected.

Use deterministic fixtures for:

```text
topic ordering
local subscriptions
candidate set
expected selected view
```

### SecureCyclon integration

Verify:

- random samples enter the Navigation candidate set;
- Navigation does not mutate the SecureCyclon view;
- a learned Navigation peer can create a real transport connection.

### Three-node integration

Verify:

- nodes begin from asymmetric SecureCyclon bootstrap;
- Navigation views improve through gossip;
- a node can discover a peer subscribed to its own topic without static configuration;
- node failure removes stale navigation links;
- restart permits rediscovery;
- topic creation/deletion recomputes finger topics without restart.

## Acceptance

Create:

```bash
./scripts/acceptance/phase-0.5.sh
```

Acceptance shall:

1. Start the Cardano devnet and Topic Registry.
2. Create at least five active topics.
3. Start the three Pub/Sub nodes using Phase 0.4 asymmetric bootstrap.
4. Subscribe the nodes to different topics.
5. Verify SecureCyclon discovery is operational.
6. Capture initial Navigation views.
7. Wait for Vicinity gossip to populate finger-topic links.
8. Verify each node's finger-topic set matches the deterministic topic ordering.
9. Verify selected peers are the closest available subscribers for their target finger topics.
10. Add a second subscription to one node and verify Navigation recomputes without restart.
11. Stop one node and verify its stale Navigation entries are eventually removed.
12. Restart it and verify rediscovery.
13. Create a new topic and verify topic ordering/finger topics update.
14. Delete a topic and verify it disappears from active ordering and Navigation state.
15. Verify Phase 0.3 signed event handling and Phase 0.4 SecureCyclon remain operational.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.5/
```

Include:

- active topic ordering;
- node subscriptions;
- initial Navigation views;
- converged Navigation views;
- finger-topic calculations;
- Vicinity gossip logs;
- failure/removal evidence;
- restart/rediscovery evidence;
- registry-change recomputation evidence;
- final acceptance result.

## Completion criterion

Phase 0.5 is complete when:

```bash
./scripts/acceptance/phase-0.5.sh
```

passes and the three-node runtime maintains Navigation views through Vicinity using SecureCyclon samples and the Cardano topic ordering.
