# Code layers and trust boundaries

Use this map when following a request through the Java modules. Packages group
related work; they do not require a separate package for every class.

| Layer | Main packages and classes | Responsibility |
| --- | --- | --- |
| Adapters | `node.adapter.http`, `node.adapter.transport`, `replication.adapter.http` | Decode requests and peer messages, authenticate and validate incoming data, translate failures to protocol responses. |
| Services | `node.service`, `replication.service` | Sit between entry points and components. They coordinate each application's publishing, forwarding, storage, lookup, recovery, and repair workflows. Services may own periodic work and lifecycle methods. |
| Components | `event`, `auth`, `persistence.core`, `persistence.adapter`, `replication.health`, `registry`, `transport`, and protocol engines | Perform focused work such as signature checks, sequence tracking, failure tracking, file and HTTP access, registry access, placement, and protocol-state updates. |
| Tools | `tools:registry-cli`, `tools:telemetry`, and the replication registry CLI in `apps:replication-server` | Provide command entry points and operational utilities. |

An application owns the workflow it runs: `node.service.EventStorageService` handles
the Pub/Sub node's storage and recovery requests, while `replication.service`
handles replica placement and repair. `libs` holds data types, protocol code, and components
that those workflows use. A component can live in `libs` even when only one
application currently uses it; `ReplicationServerClient`, `DeliveryStateStore`,
`LocalEventCreator`, and `FileEventStore` are examples. Their callers decide
when to make requests or update local state.
The topic registry CLI runs from `tools:registry-cli`; its Cardano registry
implementation remains in `libs`.

## Authentication pattern

`auth.Authenticator<Request, Result>` is the contract for a check at a trust
boundary. An implementation verifies the claim and returns the established
identity or context. Callers need to know the contract and result; cryptographic
details stay in the implementation.

| Input | Boundary check | Result |
| --- | --- | --- |
| Event from a peer | `NodeTransportListener` calls `EventValidator` before `EventProcessingService` handles it. The validator uses `PublisherEventAuthenticator` for publisher signatures and tracks duplicates. | Event acceptance or a rejection reason. |
| Event or replica submitted by HTTP | `ReplicationHttpAdapter` calls `PersistenceEventValidator`, which uses `PublisherEventAuthenticator` and checks the topic's publisher list. | Active topic state. |
| Publisher progress submitted by HTTP | `PublisherProgress` verifies its signature when decoded; `ReplicationHttpAdapter` calls `PublisherProgressAuthenticator` to check topic and publisher eligibility. | Active topic state. |
| Internal replica or repair HTTP request | `ReplicaRequestFilter` calls `HttpRequestAuthenticator`, which verifies the server signature, active membership, intended receiver, body hash, freshness, and nonce. | Authenticated server ID. |
| P2P connection | `PeerSessionHandler` calls `PeerHandshakeAuthenticator` during a two-challenge Ed25519 handshake. | Authenticated node IDs at connection establishment. |

The publisher who signed an event can differ from the peer or HTTP client that
delivered it. A valid publisher signature therefore does not authenticate an
HTTP caller. Cardano registry authorization governs registry mutations, not
replication HTTP requests.

Services still verify records fetched from other servers during lookup and
repair. These records enter through a separate receiving path. The service also
retains checks for calls initiated by local maintenance rather than an HTTP
route. Sequence and placement decisions remain with the services because they
depend on current state.

## Scope of caller authentication

The replication-server HTTP API uses three path groups:

- `/v1/client/*` accepts publisher events and serves routed event and progress lookups. Event publishers are authenticated by their event signatures; HTTP callers are not.
- `/v1/replication/*` requires a signed request from a currently registered replication server.
- `/v1/ops/*` exposes unauthenticated health and maintenance status.

The local node HTTP adapter does not authenticate callers; the testbed only
publishes it on host loopback. The P2P handshake proves key ownership before a
connection is activated. The transport does not encrypt or sign every later
frame, so an active intermediary on that connection is outside this prototype's
protection. See the [Replication Server HTTP API reference](../../documentation/replication-server-api.md)
for the route list and request details.

The local node CLI (`scripts/pubsub-node.mjs`) sends commands to
`NodeControlHttpAdapter`. In the testbed, these ports are published on host
loopback only, so this local prototype interface does not authenticate callers.
Replication servers continue to sign internal HTTP requests with their
registered Cardano payment keys; receivers check current membership before
accepting them. HTTP signatures cover the method, raw path and query, body hash,
intended receiver, timestamp, and nonce. Requests older than one minute or
repeated at the same receiver are rejected.
