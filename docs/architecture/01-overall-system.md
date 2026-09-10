# D1 — Overall system architecture

This diagram introduces the four logical planes. It is the best starting point
for a developer who needs to understand component boundaries before looking at
protocol details.

```mermaid
flowchart LR
  Apps[Applications] -->|publish, subscribe, recover| Data
  Admin[Administrators and operators] -->|registry transactions| Control

  subgraph Control[Cardano control plane]
    Devnet[Local Cardano devnet]
    Topic[Topic Registry]
    Repl[Replication Registry]
    Devnet --> Topic
    Devnet --> Repl
  end

  subgraph Data[Pub/Sub data plane]
    N1[Pub/Sub node 1]
    N2[Pub/Sub node 2]
    NN[Pub/Sub nodes 3..N]
    N1 <-->|overlay messages| N2
    N2 <-->|overlay messages| NN
    NN <-->|overlay messages| N1
  end

  subgraph Persistence[Persistence plane]
    S1[Replication server 1]
    S2[Replication server 2]
    S3[Replication server 3]
    S1 <-->|repair and inventory| S2
    S2 <-->|repair and inventory| S3
    S3 <-->|repair and inventory| S1
  end

  Topic -.->|topic policy snapshots| Data
  Topic -.->|replication factor and retention| Persistence
  Repl -.->|entry-server membership| Data
  Repl -.->|membership fingerprint| Persistence
  Data -->|publisher stores once; subscriber recovers| Persistence

  Runner[Experiment controller] -->|workload and faults| Data
  Runner -->|faults| Persistence
  Runner -->|administrative setup| Control
  Data -. observations .-> Raw[(Raw JSONL)]
  Persistence -. observations .-> Raw
  Control -. observations .-> Raw
  Runner --> Raw
  Raw --> Normalize[Validate and normalize]
  Normalize --> Parquet[(Parquet analysis datasets)]
  Parquet --> Derived[JSON and CSV summaries]
```

## How to read D1

Start in the center with the Pub/Sub data plane. Nodes exchange live events over
their peer overlay. The original publisher also submits each accepted event to
one replication entry server; receiving peers do not independently persist the
same event. The entry server calculates which servers should hold the replicas.

The Cardano plane changes much less frequently. Topic rules and replication
membership are materialized as local snapshots that runtimes poll. The dashed
arrows therefore mean “observe current configuration,” not that every event is
written to or read from the chain.

The experiment controller is outside the application architecture. It creates
test topics, drives APIs, injects faults, and collects observations. JSONL is the
authoritative record of what happened; Parquet is its validated, analysis-ready
representation.

## Local deployment

This second diagram maps the logical system to one physical development machine.
It explains where processes run and which host ports a person or script can use.

```mermaid
flowchart TB
  Host[One local machine]
  Host --> Cardano[cardano-node<br/>local testnet]
  Host --> Docker[Docker Compose project: phase-0-9]
  Docker --> Nodes[3–50 pubsub-node JVMs<br/>host 7001..7050 and 8001..8050]
  Docker --> Servers[3 replication-server JVMs<br/>host 8101..8103]
  Host --> Controller[Node.js experiment controller]
  Host --> Results[(results/scenarioId/runId)]
  Controller -->|control APIs| Nodes
  Controller -->|maintenance APIs| Servers
  Controller -->|registry CLIs| Cardano
  Controller --> Results
```

## How to read the deployment diagram

Everything runs on one host; Docker provides process and storage isolation, not
a multi-host network. Each Pub/Sub container exposes peer transport on the 7000
series and a local control API on the 8000 series. Replication APIs use the 8100
series. Inside Docker, services use stable container names and the fixed service
ports 7000 and 8100.

The node count is selectable per scenario. Three replication servers are fixed
for Phase 0.9. Generated configuration and Compose files live under `.tools`,
while experiment output is grouped by scenario and run identifier under
`results`.
