# D11 — Runtime architecture and log guide

This diagram shows the main jobs in the system and the messages between them.
Each outer box is an application boundary. It follows the paths an observer can
trace in the logs: peers connect, discover and select one another, exchange
events, and store copies for recovery. Cardano supplies configuration to those
paths through registry snapshots.

```mermaid
flowchart LR
  Users[Publisher and subscriber applications]
  Admin[Topic owners and replication operators]

  subgraph Node[Pub/Sub node · one JVM per node]
    direction TB
    API[Local control API<br/>publish, subscribe, recover, inspect]
    Events[Event processing service<br/>validate, forward, track accepted events]
    EventCreator[Local event creator<br/>assign sequence, sign, store event]
    Sampling[Peer sampling<br/>maintain a changing peer view]
    Navigation[Navigation<br/>track subscriptions and choose topic peers]
    Dissemination[Dissemination<br/>exchange topic state and forward events]
    Sessions[Peer sessions and transport<br/>handshake and message delivery]
    StorageRecovery[Event storage service<br/>queue storage, track progress, recover missed events]
    API -->|publish request| Events
    Events -->|create and store| EventCreator
    EventCreator -->|signed event envelope| Events
    API --> Navigation
    API -->|recover request| StorageRecovery
    Sampling -->|peer candidates| Navigation
    Navigation -->|topic-relevant peers| Dissemination
    Events -->|accepted event| Dissemination
    Sampling <--> Sessions
    Navigation <--> Sessions
    Dissemination <--> Sessions
    Sessions -->|incoming events| Events
    Events -->|accepted sequence; published event for storage| StorageRecovery
    StorageRecovery -->|recovered events| Events
  end

  subgraph Server[Replication server · one JVM per server]
    direction TB
    RepAPI[Replication API<br/>store, lookup, progress, internal replica calls]
    Placement[Replica placement<br/>select servers from current membership]
    Durable[(Durable event and topic progress store)]
    Repair[Replica maintenance<br/>health checks, repair, release]
    RepAPI --> Placement
    RepAPI --> Durable
    Placement -->|replica writes and lookups| PeerServer
    Repair -->|inventory and repair requests| PeerServer
    Repair -->|repair local copies| Durable
  end
  PeerServer[Peer replication server(s)<br/>same application, separate JVMs]

  subgraph Registry[Registry administration and read models]
    direction TB
    Cli[Registry tools<br/>create topics and change server membership]
    Refresh[Registry query and snapshot refresh]
    Snapshots[(Confirmed registry snapshots<br/>topic policies · active servers)]
    Refresh -->|atomic snapshot update| Snapshots
  end

  subgraph Ledger[Cardano devnet]
    Chain[Cardano ledger<br/>records transactions and runs validators]
    TopicValidator[Topic Registry validator<br/>topic policy and lifecycle]
    ReplValidator[Replication Registry validator<br/>server membership]
    Chain -->|executes on-chain| TopicValidator
    Chain -->|executes on-chain| ReplValidator
  end
  Cli -->|submit transactions| Chain
  Refresh -->|query confirmed registry state| Chain

  Users -->|publish, subscribe, recover| API
  StorageRecovery -->|store, progress and lookup| RepAPI
  RepAPI <-->|replica store and lookup| PeerServer
  Snapshots -. topic policies .-> Node
  Snapshots -. topic policies and membership .-> Server
  Admin --> Cli
```

## Reading the logs against the diagram

| Log family | What it tells you |
| --- | --- |
| `API_IN`, `API_OUT` | A local client command entering or leaving one node's control API; match by `callId` and route. |
| `HELLO_*`, `PEER_CONNECTED`, `PEER_DISCONNECTED` | The peer session and handshake path between node transports. A successful `PEER_CONNECTED` means the authenticated session is established. |
| `SAMPLING_*` | Peer sampling exchanges that maintain a node's random peer view. These exchanges do not publish application events. |
| `NAVIGATION_*` | Nodes exchange subscription and routing information to identify topic-relevant peers. |
| `DISSEMINATION_*` | Nodes exchange topic forwarding state; `EVENT_SENT` shows the event handed to the transport for a selected peer. |
| `EVENT_*` | Event validation and lifecycle: local publication, peer acceptance, duplicate/rejection, delivery, and forwarding. `EVENT_PUBLISH_IN` records the request and test flags; `EVENT_PUBLISHED` records the decision to propagate before transmission; `EVENT_RECEIVED` confirms arrival at another node. Correlate by `eventId`, topic, sequence, and peer. |
| `PERSIST_*`, `EVENT_PERSISTED`, `TOPIC_LOG_UPDATED` | The originating node asks a replication server to store an event and publisher progress; the server records durable state. |
| `RECOVER_*`, `EVENT_LOOKUP_*` | A subscriber requests missed data; the node asks replication servers for progress and event records, then validates and delivers recovered events. |
| `REPLICA_REPAIR_*`, `REPLICA_*`, `REPLICATION_MEMBERSHIP_CHANGED` | Replication servers check health and placement, then copy or release records as membership and replica responsibility change. |
| `REGISTRY_SYNCED`, `TOPIC_LOOKUP`, membership sync messages | Services observe registry snapshots. Registry changes affect routing, validation, and storage policy after they are observed. |

For request/response pairs, match `requestId`; for event processing, match
`eventId`; for replicated data, match `eventKey` and `topicId`. Timestamps can
interleave because sampling, navigation, dissemination, persistence, and
maintenance run independently. Cardano is a control plane: event payloads and
peer protocol messages travel over the Pub/Sub and replication connections,
not through the contracts.
