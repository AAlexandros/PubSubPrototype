# Cardano Pub/Sub Prototype

Java 21 prototype for a Cardano-backed Pub/Sub network. Phase 0.1 is implemented and validated: three local Pub/Sub nodes form a full mesh, exchange `HELLO`, `PING`, and `PONG` messages over length-prefixed JSON frames, persist Ed25519 node identities, and run alongside a local Cardano devnet with funded test identities.

## Repository Layout

- `apps/pubsub-node/`: runnable Pub/Sub node application and Docker image.
- `apps/replication-server/`: versioned HTTP replication service and Docker image.
- `libs/protocol-core/`: protocol messages, JSON codec, node identity storage, and `NodeId` derivation.
- `libs/transport-netty/`: Netty transport, peer sessions, framing, reconnection, and integration tests.
- `libs/persistence-api/`: persistence records, registry contracts, event/topic-log keys, and recovery result types.
- `libs/persistence-core/`: DHT placement, atomic filesystem storage, replication client, registry cache, and recovery engine.
- `libs/http-support/`: shared HTTP route, method, query, error, and JSON exchange contracts.
- `ops/config/phase-0.1/`: local three-node runtime configuration.
- `ops/infra/devnet/`: Cardano devnet Docker Compose setup, pinned tool versions, and lifecycle scripts.
- `ops/infra/phase-0.1/`: Docker Compose topology for Cardano plus the three Pub/Sub nodes.
- `ops/scripts/`: phase runtime and acceptance entrypoints.
- `specs/`: source specifications and phase task notes.
- `implementation-reports/`: implementation reports and acceptance evidence.
- `scripts/lib/`: shared cross-platform shell and Node.js utilities plus script-level contracts.
- `documentation/`: supplementary documentation, including [`documentation/scripts.md`](documentation/scripts.md), a full reference of every script in the repository.

## Requirements

- Java 21, or a JDK toolchain Gradle can resolve.
- Docker with Docker Compose.
- Bash for the `ops/scripts` and `ops/infra/devnet/scripts` entrypoints.

The Cardano tools are pinned in `ops/infra/devnet/versions.env`. If `cardano-testnet`, `cardano-node`, and `cardano-cli` are available on `PATH`, the scripts can use them directly. Otherwise Docker builds and uses the pinned `pubsub-cardano-testnet:11.0.1` runtime image.

## Build And Test

```bash
./gradlew clean build
./gradlew test
```

On Windows PowerShell:

```powershell
.\gradlew.bat clean build
.\gradlew.bat test
```

## Run Phase 0.1 Locally

Start the Cardano devnet, wait for health, fund the four phase identities, and start the three Pub/Sub nodes:

```bash
./ops/scripts/phase-0.1-up.sh
```

Stop the Pub/Sub nodes and devnet:

```bash
./ops/scripts/phase-0.1-down.sh
```

Follow the three Pub/Sub node logs:

```bash
./ops/scripts/phase-0.1-logs.sh
```

The Java nodes persist Ed25519 identities in per-node Docker volumes. `NodeId` is derived as lowercase hex `SHA-256(publicKey)`.

## Acceptance

Run the Phase 0.1 acceptance flow:

```bash
./ops/scripts/acceptance/phase-0.1.sh
```

The default acceptance soak is 30 seconds. Override it when you want a longer run:

```bash
PHASE_0_1_SOAK_SECONDS=600 ./ops/scripts/acceptance/phase-0.1.sh
```

The acceptance run verifies:

- Gradle clean build and tests.
- Cardano devnet reset, start, health, chain-tip advancement, and clean restart.
- Funding for `registry-deployer`, `node-1`, `node-2`, and `node-3`.
- Three Pub/Sub nodes reaching full mesh.
- Continuous `PING` / `PONG` exchange with RTT logging.
- Detection of a stopped node and automatic reconnection after restart.

Successful acceptance evidence is written to `implementation-reports/evidence/phase-0.1/`.

## Cardano Devnet

The devnet integration uses `cardano-testnet` rather than a custom chain implementation. Runtime values for Java/Cardano integration are exported to:

```text
ops/infra/devnet/runtime/network.env
```

That file includes:

- `CARDANO_NODE_SOCKET_PATH`
- `CARDANO_NETWORK_MAGIC`
- `REGISTRY_DEPLOYER_ADDRESS`
- `NODE_1_CARDANO_ADDRESS`
- `NODE_2_CARDANO_ADDRESS`
- `NODE_3_CARDANO_ADDRESS`

`fund-identities.sh` locates a generated testnet funding UTxO, submits real funding transactions, waits for confirmation, and fails if the target identities are not funded.

## SecureCyclon peer sampling (Phase 0.4)

Run the three-node acceptance with:

```bash
./scripts/acceptance/phase-0.4.sh
```

The phase-specific configuration in `ops/config/phase-0.4/` is asymmetric:
node-1 has no seeds; node-2 and node-3 each seed from node-1. Both leaf nodes
learn further peers through SecureCyclon. The phase's Compose file is
`ops/infra/phase-0.4/compose.yaml` and uses the existing identity volumes.

`peerSampling` configures `advertisedHost`, `viewSize`, `swapLength`,
`cycleIntervalMs`, `ageThreshold`, and `randomSeed`. The advertised host must be
reachable by the other nodes. Old configurations without this section retain
their previous behavior. Higher layers consume `PeerSamplingService`; the
read-only `GET /v1/peer-sampling/view` control endpoint exposes snapshots.

Acceptance archives previous generated devnet state under `.tools/phase-0.4-devnet-backups/`,
creates and funds a fresh Cardano test network, and captures results under
`implementation-reports/evidence/phase-0.4/`. Pub/Sub identity volumes remain persistent.
The containers remain running for inspection after acceptance.

The temporary Phase 0.3 event forwarding path continues over established sessions;
it is not the D2 dissemination layer. See [the protocol mapping](libs/securecyclon/README.md)
for reference behavior and runtime adaptations.

## Navigation layer / Vicinity (Phase 0.5)

Run the three-node acceptance with:

```bash
./scripts/acceptance/phase-0.5.sh
```

The `libs/navigation` module implements the D2 Navigation Layer on top of
Phase 0.4 SecureCyclon: deterministic topic ordering (active topic ids sorted
lexicographically into ordinals `0..T-1`), finger-topic targets at distances
`b^i` clockwise/anticlockwise (default `b = 2`), a bounded per-target-topic
Navigation view (default `c = 2` peers per target), and a Vicinity gossip
cycle exchanged over new `NAVIGATION_REQUEST`/`NAVIGATION_RESPONSE` messages.

Subscriptions are local node state (not on-chain): a persistent
newline-delimited `SubscriptionStore` backs

```text
POST   /v1/subscriptions/{topicId}
DELETE /v1/subscriptions/{topicId}
GET    /v1/subscriptions
GET    /v1/navigation/view
```

The Navigation layer only reads `PeerSamplingService.view()` for random
candidates; it never mutates the SecureCyclon view. Raw SecureCyclon samples
enter the Navigation candidate pool with unknown subscriptions and are
superseded once their real subscriptions are learned via gossip. Navigation
candidates without an active transport session reuse the Phase 0.4 dynamic
connection mechanism.

`navigation` configures `capacity`, `routingBase`, `cycleIntervalMs`,
`staleAfterMs`, and an optional `subscriptionsPath`. When the Cardano registry's
active topic set changes, the node recomputes topic ordering and finger
topics without a restart (the Navigation view is cleared and repopulated
through subsequent gossip cycles, since ordinal numbers can refer to a
different topic once the active set changes).

Acceptance creates at least five active topics, subscribes the three nodes to
different topics, verifies finger-topic computation and Vicinity selection,
adds a subscription and confirms recomputation without restart, verifies
stale-link removal after a node stop and rediscovery after restart, and
verifies topic creation/deletion updates topic ordering without restart.
Results are captured under `implementation-reports/evidence/phase-0.5/`.

## Current Status

Phase 0.9 provides the integrated, local-only experiment testbed. A generated
Compose topology runs one local Cardano devnet, three replication servers, and
3–30 real Pub/Sub JVM nodes. The controller captures eleven correlated raw
JSONL datasets, validates and converts them to canonical Apache Parquet, keeps
raw inputs intact, combines repetitions, and emits derived JSON/CSV summaries.

Start the default three-node testbed or a larger E1 topology with:

```bash
./scripts/testbed/up.sh
./scripts/testbed/up.sh --nodes 10
./scripts/testbed/status.sh
./scripts/testbed/down.sh
```

Run and aggregate scenarios with:

```bash
./scripts/experiments/run.sh ops/config/phase-0.9/scenarios/e1-scaling-10.yaml
./scripts/experiments/aggregate.sh results
```

The full five-node integrated acceptance flow is:

```bash
./scripts/acceptance/phase-0.9.sh
```

Architecture diagrams are indexed in
[`docs/architecture/README.md`](docs/architecture/README.md), and the generated
schema reference is [`results/data-dictionary.md`](results/data-dictionary.md).

Phase 0.8 adds automatic replica maintenance to the independent persistence
path beside Hybrid Dissemination. Replication servers actively probe only peers
that share responsibility for local records, confirm failure after configurable
consecutive attempts, exchange bounded metadata inventories, and pull validated
event/topic-log repairs after failures, joins, leaves, and replication-factor
changes. Replica release waits for the current responsible set to confirm
storage under the same membership version.

Three registered replication servers assign signed events and publisher topic
logs in a 256-bit DHT, store atomic filesystem replicas, and serve lookup from
any entry server. Pub/Sub nodes persist delivery cursors and recover missed,
revalidated events through `POST /v1/events/recover/{topicId}` without routing
live dissemination through the DHT.

Replication services expose `POST /v1/events`, `GET /v1/events/{eventKey}`,
`GET /v1/topics/{topicId}/publishers` (with optional `sinceTimestamp`), and
`GET /v1/health`, `GET /v1/maintenance/status`, and
`GET /v1/maintenance/replicas`. Only the original publishing node submits persistence work;
receiving subscribers remain on the delivery path only.

Run the full acceptance flow with:

```bash
./scripts/acceptance/phase-0.8.sh
```

Latest implementation report: [Phase 0.8](implementation-reports/phase-0.8-implementation-report.md).
