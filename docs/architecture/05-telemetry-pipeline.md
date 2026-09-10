# D5 — Telemetry and results pipeline

This diagram explains how a live experiment becomes a reproducible dataset. The
key rule is that detailed raw observations are retained; summaries can always be
recomputed with different analysis choices.

```mermaid
flowchart LR
  Nodes[Pub/Sub logs and control APIs] --> Collector[Experiment observer]
  Servers[Replication logs and maintenance APIs] --> Collector
  Cardano[Registry CLIs and devnet] --> Collector
  Docker[Container resource counters] --> Collector
  Scenario[Scenario, seed, and fault schedule] --> Collector
  Collector --> JSONL[(11 raw JSONL datasets)]
  JSONL --> Validate[Catalog validation and type normalization]
  JSONL --> Preserve[Preserved raw run inputs]
  Validate --> Parquet[(11 per-run Parquet datasets)]
  Validate --> Summaries[Derived metric calculation]
  Summaries --> JSON[summary.json]
  Summaries --> CSV[summary.csv]
  JSONL --> Combine[Combine raw records across runs]
  Combine --> CombinedRaw[(results/combined/raw)]
  CombinedRaw --> ValidateCombined[Validate and normalize]
  ValidateCombined --> CombinedParquet[(results/combined/parquet)]
  Catalog[Typed dataset catalog] --> Validate
  Catalog --> ValidateCombined
  Catalog --> Dictionary[data-dictionary.md]
```

## How to read D5

The experiment observer is a test harness, not a message broker. It drives the
scenario while sampling control APIs, collecting structured runtime logs,
reading Docker counters, and recording Cardano CLI activity. It correlates those
sources with one run identity and writes one JSON Lines file per dataset.

Raw JSONL is authoritative because it preserves individual observations.
Normalization checks required fields and types and produces Parquet for efficient
analysis. JSON and CSV summaries are conveniences, not substitutes for the raw
records. Cross-run aggregation concatenates compatible raw records and then
normalizes the combined data; it does not average away the underlying samples.

Every record includes run, scenario, repetition, seed, UTC time, and monotonic
time where applicable. Domain identifiers such as node ID, topic ID, event ID,
event key, and server ID connect observations across datasets.

## Logical data relationships

This is a join map for analysts, not a transactional database schema. The
cardinality symbols are intentionally optional because telemetry may observe one
side of an interaction without observing the other.

```mermaid
erDiagram
  RUNS ||--o{ EVENT_LIFECYCLE : runId
  RUNS ||--o{ MESSAGE_TRANSMISSIONS : runId
  RUNS ||--o{ OVERLAY_EDGES : runId
  RUNS ||--o{ PROTOCOL_CYCLES : runId
  RUNS ||--o{ SUBSCRIPTIONS : runId
  RUNS ||--o{ PERSISTENCE_OPERATIONS : runId
  RUNS ||--o{ REPLICA_STATE : runId
  RUNS ||--o{ FAULTS : runId
  RUNS ||--o{ RESOURCE_SAMPLES : runId
  RUNS ||--o{ CARDANO_TRANSACTIONS : runId
  EVENT_LIFECYCLE }o--o{ PERSISTENCE_OPERATIONS : eventId
  PERSISTENCE_OPERATIONS }o--o{ REPLICA_STATE : eventKey
  SUBSCRIPTIONS }o--o{ EVENT_LIFECYCLE : topicId
```

## How to read the relationship diagram

`RUNS` is the anchor: one run can have zero or many observations in every
specialized dataset. An event ID connects live lifecycle observations to store
or lookup activity. The deterministic event key connects persistence operations
to physical replica state. Topic ID connects subscription state to events that
could be delivered under that subscription.

These are analytical joins rather than enforced foreign keys. For example, a
rejected live event may have lifecycle observations but no persistence record,
and a maintenance inventory may contain a replica created before the observer's
current sampling window.
