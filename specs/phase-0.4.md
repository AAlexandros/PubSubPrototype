# Phase 0.4 — SecureCyclon Peer Sampling Layer

## Goal

Implement the bottom layer of the D2 three-layer overlay: **SecureCyclon peer sampling**.

The layer shall maintain a small dynamic view of random live peers and provide fresh peer samples to later protocol layers.

The existing static peer configuration shall be used only for bootstrap.

## Protocol source

D2 defines the role and required properties of the Peer Sampling Layer, but does not contain the complete SecureCyclon state machine.

For protocol-level behavior, use the existing Java PeerNet SecureCyclon simulation as the reference implementation. The implementation shall preserve its:

- view structure;
- gossip cycle;
- peer-selection rule;
- exchange contents;
- link freshness/aging rules;
- secure fresh-link generation limits;
- validation rules;
- view update/replacement rules.

Do not replace SecureCyclon with a simplified random-peer protocol.

## Architecture

Add:

```text
libs/peer-sampling-api/
libs/securecyclon/
scripts/acceptance/phase-0.4.sh
implementation-reports/evidence/phase-0.4/
```

Update:

```text
libs/protocol-core/
libs/transport-netty/
apps/pubsub-node/
ops/config/
ops/infra/
```

Keep SecureCyclon independent from:

- Topic Registry logic;
- event validation;
- Navigation/Vicinity;
- Dissemination/Hybrid Dissemination.

## Peer descriptor

Define the peer descriptor required by SecureCyclon.

At minimum, the runtime must be able to resolve:

```text
nodeId
host
port
```

SecureCyclon-specific metadata shall follow the PeerNet implementation.

Descriptors exchanged over the network must be validated before entering the local view.

## SecureCyclon view

Each node maintains a bounded SecureCyclon view.

Requirements:

- configurable view size;
- no self entry;
- no duplicate `nodeId`;
- stale/departed peers are eventually removed;
- new peers can replace existing entries;
- the view changes through SecureCyclon gossip, not through global membership knowledge.

For the initial three-node runtime, the effective view size may be bounded by the number of other live nodes.

## Wire protocol

Add the SecureCyclon message types required by the reference implementation.

Keep them separate from:

```text
HELLO
HELLO_ACK
PING
PONG
EVENT
```

All SecureCyclon messages shall use the existing framed transport and protocol-version checks.

## Dynamic transport

The transport must support connections to peers learned at runtime.

SecureCyclon shall be able to:

1. receive a previously unknown peer descriptor;
2. request/connect to that peer;
3. complete the existing `HELLO` handshake;
4. use the peer in later gossip cycles.

Static configuration must not be required for dynamically discovered peers.

## Bootstrap

For Phase 0.4 use an asymmetric bootstrap configuration.

Example:

```text
node-1: bootstrap seed
node-2: knows node-1
node-3: knows node-1
```

`node-2` and `node-3` must be able to discover each other through the peer-sampling protocol.

Bootstrap peers are seeds only. They must not be pinned permanently in the SecureCyclon view.

## Secure behavior

Implement the SecureCyclon protections present in the PeerNet simulation.

At minimum, the runtime shall reject exchanges that violate the protocol's rules for fresh-link creation or exchange structure.

Log rejected exchanges and their reason.

## Integration with existing runtime

Keep:

- persistent Ed25519 node identity;
- `NodeId`;
- `HELLO` handshake;
- PING/PONG health checks;
- Phase 0.3 event model and validation.

SecureCyclon manages the **peer-sampling view**. It does not yet define topic navigation or final event dissemination neighbors.

Phase 0.3 baseline event forwarding may remain temporarily available, but it must not be treated as the D2 dissemination layer.

## Logging

Add structured events:

Include where applicable:

```text
nodeId
peerNodeId
viewSize
cycle
reason
```

## Tests

### Protocol tests

Using the PeerNet simulation as the behavioral reference, verify:

- gossip-peer selection;
- exchange construction;
- exchange validation;
- view update/replacement;
- freshness/aging behavior;
- duplicate rejection;
- self-entry rejection;
- bounded view size;
- secure fresh-link generation limits.

Where possible, create deterministic conformance fixtures:

```text
initial view
random seed
incoming exchange
expected resulting view
```

### Transport integration tests

Verify:

- SecureCyclon messages encode/decode correctly;
- a descriptor learned through gossip can create a real transport connection;
- duplicate connections are handled safely;
- malformed SecureCyclon messages do not corrupt the view.

### Three-node tests

Verify:

- node-2 and node-3 start knowing only node-1;
- node-2 discovers node-3 or node-3 discovers node-2 through SecureCyclon;
- views remain bounded;
- stopping one node causes stale references to disappear;
- restarting it allows rediscovery.

## Acceptance

Create:

```bash
./scripts/acceptance/phase-0.4.sh
```

The acceptance flow shall:

1. Build and test the repository.
2. Start the existing Cardano/devnet environment.
3. Start the three Pub/Sub nodes with asymmetric bootstrap configuration.
4. Verify SecureCyclon starts on all nodes.
5. Verify node-2 and node-3 are not statically configured with each other.
6. Wait until SecureCyclon discovers the missing peer relationship.
7. Capture each node's peer-sampling view.
8. Verify all views are valid and bounded.
9. Stop `node-3`.
10. Verify stale `node-3` references are eventually removed according to the protocol.
11. Restart `node-3`.
12. Verify it is rediscovered through SecureCyclon.
13. Submit an invalid SecureCyclon exchange and verify rejection.
14. Verify PING/PONG and the Phase 0.3 event path still operate after convergence.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.4/
```

Include:

- bootstrap configuration;
- initial peer-sampling views;
- gossip-cycle logs;
- peer-discovery evidence;
- converged views;
- node failure/removal evidence;
- node restart/rediscovery evidence;
- invalid exchange rejection;
- final acceptance result.

## Completion criterion

Phase 0.4 is complete when:

```bash
./scripts/acceptance/phase-0.4.sh
```

passes and the three-node runtime maintains its peer-sampling views through SecureCyclon rather than a statically configured full mesh.
