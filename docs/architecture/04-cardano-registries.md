# D4 — Cardano and registry architecture

Cardano provides shared, auditable control-plane state. The local Java services
do not query the chain for every operation; registry clients materialize
validated chain state into atomic snapshots that can be polled cheaply.

```mermaid
flowchart LR
  Owners[Topic owners and administrators] --> TopicCLI[Topic registry CLI]
  Operators[Replication operators] --> ReplCLI[Replication registry CLI]
  Identities[Funded local signing identities] --> TopicCLI
  Identities --> ReplCLI
  TopicCLI -->|signed transaction| Devnet[Local Cardano devnet]
  ReplCLI -->|signed transaction| Devnet
  Devnet --> TopicContract[Topic Registry validator and policy]
  Devnet --> ReplContract[Replication Registry validator]
  Devnet -->|read-only CLI query| NodeSync[Each Pub/Sub node]
  Devnet -->|read-only CLI query| ServerSync[Each replication server]
  NodeSync --> NodeViews[(Local atomic topic and membership views)]
  ServerSync --> ServerViews[(Local atomic topic and membership views)]
```

## How to read D4

The left side shows who is authorized to change state. Topic owners use funded
signing identities for topic lifecycle and policy transactions. Replication
operators use their identities to register or unregister storage servers. The
validators enforce the allowed state transitions on the local Cardano ledger.

Each application independently queries its configured Cardano node through a
read-only socket. It validates the returned UTxOs and replaces its in-memory
view as a unit. A failed query preserves the prior view. The first successful
query is required for registry readiness, and a view older than the configured
maximum age cannot authorize new topic or membership decisions. The configured
node and socket are trusted; Java does not independently verify the chain.

Event payloads and replica inventories are absent by design. Cardano records
governance and discovery data, while the data and persistence planes handle the
high-volume event path.

## Cardano CLI execution

The registry selects one command backend at construction time:

- `HOST` runs the installed `cardano-cli` and requires network magic and a node socket path.
- `DEVNET` runs `cardano-cli` through the devnet Docker Compose service and requires network magic.

Shared Java execution and payment-transaction types live under
`org.pubsub.prototype.cardano.cli`. The Topic Registry implementation lives
under `org.pubsub.prototype.registry.topic.cardano`; the separate Replication
Registry implementation lives under `org.pubsub.prototype.registry.replication.cardano`.
Each registry keeps its own deployment, datum, mutation, and transaction code.
The replication services consume a persistence snapshot from
`org.pubsub.prototype.persistence`, without depending on the Cardano registry
implementation.

There is no snapshot fallback. Application YAML selects `HOST`, while registry
administration scripts select `DEVNET` by default.
Set `CARDANO_CLI_BACKEND=HOST` to make those scripts use an installed CLI
instead.

For topic mutations, `CardanoTopicRegistry` selects UTxOs, constructs typed
commands, and executes the build, sign, and submit steps through its command
runner. The artifact helper only names and writes transaction files.

`TopicRegistry.activeTopicsSnapshot()` returns an immutable list of active
topics ordered by topic ID; `allTopicsSnapshot()` returns the same ordered list
including deletion tombstones; and
`findTopicState(topicId)` looks up one topic's state. The node's registry
synchronizer needs tombstones so it can observe topic deletion. Topic and
replication registry writes both poll for confirmed state through
`CardanoConfirmationWaiter` after submitting a transaction.

`TopicStateProvider` supplies the topic state used by event authorization and
validation. Each Pub/Sub node and replication server polls
`TopicRegistry.allTopicsSnapshot()` into its own
`RegistryObserver<List<TopicState>>`. The observer records when either registry
list was read, so the list elements do not carry an observation timestamp. Event
checks read from the fresh list through `TopicStateProvider`, so an event does
not trigger a Cardano query. The same list supplies active topics to routing
and replica maintenance.

Replication Registry administration also signs Cardano transactions, using its
own transaction workflow. The running replication servers separately sign
internal HTTP requests to other replication servers; those are not Cardano
transactions.

Deployment metadata and the configured signing identity are loaded lazily and
cached for the lifetime of a `CardanoTopicRegistry` instance. Changes to
`deployment.env`, the configured signer, its payment address or key files, the
network environment, or the CLI backend therefore require the owning process
to be restarted. On-chain topic changes remain dynamic and continue to be
observed through registry queries without a restart.

The configured signer is the only identity read from the local `keys/`
directory. Topic owner and administrator arguments cross the registry boundary
as public 56-character Cardano payment-key hashes; the owner supplies those
hashes through a separate trusted channel. The Java and shell boundaries reject
identity names for these fields. Local acceptance tests use
`scripts/registry/payment-key-hash.sh` only to derive hashes for generated
devnet identities.

For the complete generated command shapes, their purposes, and their owning
scripts or Java components, see the
[Cardano CLI command reference](../../documentation/cardano-cli.md).

The `DEVNET` backend expands a command to:

```text
docker compose \
  --env-file <devnet>/versions.env \
  -f <devnet>/compose.yaml \
  exec -T \
  -e CARDANO_NODE_SOCKET_PATH=/devnet/runtime/testnet/socket/node1/sock \
  cardano-node cardano-cli <arguments>
```

Paths inside `<arguments>` that belong to the host devnet directory are mapped
under the container's `/devnet` mount.

### Local Cardano pool node

The Docker devnet currently creates one Cardano pool node. This is the blockchain
process that validates transactions, maintains the test ledger, and produces
blocks; it is unrelated to the Pub/Sub application nodes. The CLI connects to it
through `/devnet/runtime/testnet/socket/node1/sock` inside the container. The
host devnet runtime directory is mounted at `/devnet/runtime`, so the same socket
is also available from the host through the generated network configuration.

One pool node is enough for contract and integration testing. A multi-pool devnet
would create additional node sockets, but the CLI could still use node 1 as its
single query and submission endpoint.

# D10 — Administrative actions and runtime observation

D10 expands D4 over time. It distinguishes an explicit administrative write
from the independent polling by long-running components.

```mermaid
sequenceDiagram
  actor Admin
  participant TopicCLI as Topic CLI
  participant ReplCLI as Replication CLI
  participant Chain as Cardano devnet
  participant Snapshot as Registry snapshot tools
  participant Cache as Atomic JSON snapshots
  participant Nodes as Pub/Sub nodes
  participant Servers as Replication servers

  Admin->>TopicCLI: create, update, or delete topic
  TopicCLI->>Chain: build, sign, and submit transaction
  Chain-->>TopicCLI: confirmed topic UTxO state
  TopicCLI->>Snapshot: refresh topic view
  Snapshot->>Cache: atomically replace topics snapshot
  Nodes->>Cache: poll topics and policies
  Cache-->>Nodes: active topic view
  Servers->>Cache: poll replication factor and retention
  Cache-->>Servers: current topic settings

  Admin->>ReplCLI: register or unregister server
  ReplCLI->>Chain: submit signed registry transition
  Chain-->>ReplCLI: confirmed server datum
  ReplCLI->>Snapshot: refresh membership view
  Snapshot->>Cache: atomically replace servers snapshot
  Nodes->>Cache: poll entry-server membership
  Cache-->>Nodes: active endpoints
  Servers->>Cache: poll membership
  Cache-->>Servers: members and new fingerprint
```

## How to read D10

Each administrative operation has two stages: commit the authoritative change
on Cardano, then refresh the local read model. Application services discover the change on
their next polling cycle. The sequence therefore has an expected observation
delay; a successful transaction does not imply that every JVM has already
adopted the new view.

A topic update can change validation policy, retention, or replication factor.
A membership update can change entry-server selection and replica placement.
Those downstream reactions are shown in D2 and D3 rather than repeated here.
