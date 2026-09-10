# Phase 0.9 — Local Integrated Testbed, Telemetry, and Architecture Documentation

## Goal

Build the **fully integrated local prototype testbed**.

Phase 0.9 focuses on one machine only. All major components implemented in Phases 0.1–0.8 shall be glued together into one reproducible local environment that can:

- start the complete system from scratch;
- run end-to-end workloads locally;
- collect rich raw telemetry across all layers;
- normalize results into analysis-friendly datasets;
- provide a stable base for later plotting and paper writing;
- document the architecture with Mermaid diagrams.

This phase does **not** include public testnet deployment or multi-host distributed execution. Those can come later.

## Scope

Phase 0.9 shall integrate the existing local components:

- local Cardano devnet;
- Topic Registry;
- Replication Registry;
- Pub/Sub nodes;
- replication servers;
- SecureCyclon;
- Navigation / Vicinity;
- Hybrid Dissemination;
- persistence, recovery, and replica maintenance;
- experiment runner;
- telemetry pipeline;
- result aggregation;
- architecture documentation.

The emphasis is:

```text
complete local system
        +
rich raw data
        +
clear architecture documentation
```

## Deliverables

Add or complete:

```text
docs/architecture/
docs/architecture/README.md
docs/architecture/diagrams/
scripts/testbed/
scripts/experiments/
scripts/acceptance/phase-0.9.sh
implementation-reports/evidence/phase-0.9/
results/
```

## Local integrated testbed

Create a single local testbed entrypoint, for example:

```bash
./scripts/testbed/up.sh
./scripts/testbed/down.sh
./scripts/testbed/reset.sh
./scripts/testbed/status.sh
```

The testbed shall bring up:

```text
1 local Cardano devnet
3 Pub/Sub nodes
3 replication servers
all required registries/contracts
telemetry output directories
```

The testbed must be reproducible from a clean local machine state.

A single command shall be able to bootstrap the whole system and make it ready for experiments.

## Integrated runtime model

The local testbed shall represent this complete runtime:

```text
local Cardano devnet
        │
        ├── Topic Registry
        └── Replication Registry
                │
                ├── Pub/Sub nodes
                │     ├── transport
                │     ├── SecureCyclon
                │     ├── Navigation
                │     ├── Dissemination
                │     ├── publisher/subscriber API
                │     └── recovery state
                │
                └── Replication servers
                      ├── DHT placement
                      ├── replica maintenance
                      └── stored event/topic-log state
```

## Local orchestration

The local orchestration scripts shall support:

```text
bootstrap
start
stop
restart
wipe state
collect logs
collect results
run workload
run acceptance
```

The orchestration must understand process roles:

```text
cardano
pubsub-node
replication-server
experiment-controller
```

## Configuration

Provide a local configuration set under:

```text
ops/config/phase-0.9/
```

It shall capture:

```text
ports
paths
node IDs
replication server IDs
topic configuration
telemetry level
sampling intervals
workload parameters
result locations
```

All local testbed scripts shall use these configuration files rather than hard-coded paths where practical.

## Experiment runner

Create a local experiment runner, for example:

```bash
./scripts/experiments/run.sh <scenario.yaml>
```

A scenario shall define at least:

```text
scenarioId
repetition
randomSeed
nodeCount
topicCount
subscriptionDistribution
publisherCount
eventCount
eventRate
payloadSize
warmupDuration
measurementDuration
telemetryLevel
```

Also include relevant protocol parameters:

```text
SecureCyclon settings
Navigation settings
Dissemination settings
replicationServerCount
replicationFactor
fault schedule
```

Phase 0.9 is local-only, so all experiment runs execute on the same machine using the real Java components.

## Telemetry goal

The main telemetry rule is:

> Raw observations are authoritative. Any aggregate metric, plot, or paper table must be reproducible from stored raw data.

Do not record only final averages.

Record detailed structured events from all major components.

## Required datasets

Phase 0.9 shall produce at least these datasets:

```text
runs
event_lifecycle
message_transmissions
overlay_edges
protocol_cycles
subscriptions
persistence_operations
replica_state
faults
resource_samples
cardano_transactions
```

All datasets shall share stable identifiers where relevant:

```text
runId
scenarioId
repetition
randomSeed
timestampUtc
monotonicTimeNs
nodeId
serverId
topicId
eventId
eventKey
publisherKeyId
sequenceNumber
peerNodeId
```

## Raw storage and normalization

Runtime processes shall write raw structured telemetry as:

```text
JSON Lines (*.jsonl)
```

After each run, the pipeline shall normalize the data into:

```text
Parquet (*.parquet)
```

CSV may be produced as a derived export, but Parquet is the canonical analysis format.

Pipeline:

```text
runtime
   ↓
raw JSONL
   ↓
validation / normalization
   ↓
Parquet
   ↓
derived summaries
   ↓
optional CSV exports
```

## Result layout

Use a layout such as:

```text
results/
  <scenarioId>/
    <runId>/
      scenario.yaml
      metadata.json
      raw/
      parquet/
      derived/
```

Also maintain:

```text
results/combined/
```

for cross-run datasets.

## Telemetry datasets

### runs

One record per run.

Include:

```text
runId
scenarioId
repetition
randomSeed
Git commit
Java version
startTime
endTime
protocol configuration
workload configuration
telemetry configuration
```

### event_lifecycle

Record event-stage observations.

Stages shall include:

```text
PUBLISHED
RECEIVED
ACCEPTED
FORWARDED
DUPLICATE
REJECTED
PERSIST_SUBMITTED
PERSISTED
RECOVERY_FETCHED
RECOVERY_DELIVERED
```

### message_transmissions

Record every actual protocol message send.

Include:

```text
fromNodeId
toNodeId
protocolLayer
messageType
topicId
eventId
bytes
success
```

Protocol layers:

```text
TRANSPORT
SECURECYCLON
NAVIGATION
DISSEMINATION
PERSISTENCE
```

### overlay_edges

Snapshot logical overlay relationships.

Include:

```text
layer
nodeId
peerNodeId
topicId
role
freshness
```

### protocol_cycles

Record each SecureCyclon, Navigation, Dissemination, and maintenance cycle.

### subscriptions

Record subscribe/unsubscribe actions and effective subscription state.

### persistence_operations

Record:

```text
STORE
LOOKUP
RECOVERY_LOOKUP
REPAIR
RELEASE
TOPIC_LOG_UPDATE
```

### replica_state

Record replica ownership/presence/responsibility over time.

### faults

Record explicitly injected failures or changes.

### resource_samples

Sample at least:

```text
cpuPercent
rssBytes
heapUsedBytes
networkRxBytes
networkTxBytes
diskReadBytes
diskWriteBytes
storedEventCount
storedEventBytes
openConnections
```

### cardano_transactions

Record local Cardano transactions for topic and replication registries.

## Derived metrics

The aggregation tool shall derive, without discarding raw inputs:

### dissemination

```text
hit ratio
delivery coverage over time
latency percentiles
message overhead
duplicate count
load distribution
```

### overlay behavior

```text
SecureCyclon convergence
Navigation convergence
same-topic discovery time
ring correctness
ring repair time
peer replacement rate
view churn
```

### persistence

```text
persistence latency
lookup latency
offline recovery latency
recovered event count
replica repair latency
under-replicated duration
R-change convergence time
```

### resources

```text
CPU usage
memory usage
network usage
storage growth
```

### Cardano local control-plane

```text
topic create/update/delete latency
replication-server register/unregister latency
registry observation delay
```

## Local scenarios

Provide at least the following local experiment scenario families.

### E1 — Local dissemination scaling

Run with increasing local node counts.

Initial targets:

```text
N = 3
N = 10
N = 30
```

If the local machine can handle more, larger runs may be added.

### E2 — Local failure/churn

Inject node failures/restarts and measure overlay and dissemination recovery.

### E3 — Local multi-topic navigation

Use multiple topics and varied subscription distributions.

### E4 — Local persistence and offline recovery

Take a subscriber offline, publish events, restore it, and recover missing events.

### E5 — Local replication-server repair

Stop one responsible replication server and measure detection and repair.

### E6 — Local replication-factor change

Change `R` and measure convergence.

## Aggregation tool

Create:

```bash
./scripts/experiments/aggregate.sh <results-dir>
```

It shall:

- validate schemas;
- convert JSONL to Parquet;
- combine repetitions;
- generate derived summaries;
- export selected CSVs.

It must not delete raw JSONL.

## Data dictionary

Generate:

```text
results/data-dictionary.md
```

It shall document:

```text
dataset name
field name
type
unit
meaning
nullable
source component
```

## Architecture documentation

Phase 0.9 shall include Mermaid-based architecture documentation under:

```text
docs/architecture/
```

All diagrams shall be checked into the repository as Markdown files containing Mermaid code blocks.

They should be simple, readable, and useful for both implementation understanding and later paper preparation.

## Required Mermaid diagrams

At minimum, create the following.

### D1 — Overall system architecture

A high-level component diagram showing:

- local Cardano devnet;
- Topic Registry;
- Replication Registry;
- Pub/Sub nodes;
- replication servers;
- experiment runner;
- telemetry/results pipeline.

### D2 — Pub/Sub node internal architecture

Show internal modules and their relationships:

- transport;
- peer/session management;
- SecureCyclon;
- Navigation;
- Dissemination;
- event validation;
- event publisher;
- event receiver;
- recovery state;
- local APIs.

### D3 — Replication server internal architecture

Show:

- registry synchronization;
- membership view;
- placement calculator;
- local storage;
- topic-log store;
- failure detector;
- replica maintenance manager;
- repair/release logic;
- replication APIs.

### D4 — Cardano/registry architecture

Show:

- local devnet;
- Topic Registry contract;
- Replication Registry contract;
- registry clients/synchronizers;
- admin/operator identities.

### D5 — Telemetry and results pipeline

Show:

- runtime components;
- raw JSONL telemetry;
- normalization;
- Parquet datasets;
- derived summaries;
- optional CSV exports.

### D6 — Time-progress / lifecycle flow

Create a flow diagram showing how the full system behaves over time, for example:

```text
bootstrap
→ start registries
→ start replication servers
→ start pubsub nodes
→ overlay convergence
→ topic creation
→ subscription
→ event publication
→ dissemination
→ persistence
→ offline node
→ more events
→ recovery
→ repair/failure handling
→ results collection
```

### D7 — Event publication and dissemination sequence diagram

Show:

- publisher node;
- dissemination peers;
- replication entry server;
- responsible replicas;
- subscriber acceptance.

### D8 — Offline recovery sequence diagram

Show:

- recovering subscriber;
- topic log lookup;
- event-key reconstruction;
- replication-server lookup;
- validation;
- replay/delivery.

### D9 — Replica repair sequence diagram

Show:

- failure detector;
- surviving replica server;
- replacement server;
- repair notification;
- fetch;
- store;
- safe release.

### D10 — Topic/topic-registry and replication-registry interaction diagram

Show administrative actions and how runtime components observe changes.

## Additional useful diagrams

If simple and useful, also add:

- local deployment diagram showing ports/processes;
- data model relationship diagram;
- state machine diagram for event lifecycle;
- state machine diagram for replication-server maintenance.

Do not add diagrams only for completeness; they must be readable and useful.

## Documentation structure

A recommended documentation structure is:

```text
docs/architecture/README.md
docs/architecture/01-overall-system.md
docs/architecture/02-pubsub-node.md
docs/architecture/03-replication-server.md
docs/architecture/04-cardano-registries.md
docs/architecture/05-telemetry-pipeline.md
docs/architecture/06-time-flows.md
docs/architecture/07-sequence-diagrams.md
```

The README shall explain which diagram lives where and what it is intended to show.

## Acceptance

Create:

```bash
./scripts/acceptance/phase-0.9.sh
```

Acceptance shall:

1. Start from a clean local environment.
2. Bring up the full local integrated testbed.
3. Verify Topic Registry and Replication Registry are operational.
4. Start Pub/Sub nodes and replication servers.
5. Verify SecureCyclon, Navigation, and Dissemination converge locally.
6. Create at least one topic.
7. Subscribe nodes.
8. Publish events.
9. Verify dissemination.
10. Verify persistence.
11. Stop one subscriber and recover missed events.
12. Stop one responsible replication server and verify repair.
13. Run at least one local telemetry scenario with more than three nodes.
14. Verify raw JSONL datasets are produced.
15. Normalize the datasets into Parquet.
16. Verify derived summaries are produced.
17. Verify raw data remains preserved.
18. Verify the data dictionary is generated.
19. Verify all required architecture diagram files exist and render as Mermaid source blocks.
20. Store acceptance evidence.

## Evidence

Store under:

```text
implementation-reports/evidence/phase-0.9/
```

Include:

- local testbed bootstrap evidence;
- integrated component startup evidence;
- topic and replication registry evidence;
- dissemination/persistence/recovery evidence;
- repair evidence;
- scenario execution evidence;
- telemetry file inventory;
- Parquet output inventory;
- data dictionary evidence;
- architecture documentation inventory;
- final acceptance result.

Large experiment results belong under:

```text
results/
```

not in the implementation-report evidence directory.

## Completion criterion

Phase 0.9 is complete when:

1. the full prototype runs locally as one integrated testbed;
2. all major components from Phases 0.1–0.8 are glued together and exercised end to end;
3. rich raw telemetry is produced and normalized into reusable datasets;
4. the results can later support many different plots without re-instrumenting the system;
5. the repository contains clear Mermaid architecture and flow documentation for the whole system.
