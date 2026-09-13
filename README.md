# Cardano Pub/Sub Prototype

A Java 21 prototype of a decentralized, topic-based publish/subscribe system.
Cardano provides the control plane for topic and replication-server registries;
live event payloads travel between Pub/Sub nodes and are stored by independent
replication servers for later recovery.

The current implementation is **Phase 0.9**: a local, reproducible testbed with
3-50 real Pub/Sub JVMs, three replication servers, a Cardano devnet, fault
injection, and JSONL/Parquet telemetry.

## System at a glance

The system has four cooperating planes:

- **Control:** two Aiken/Cardano registries describe topics, authorization,
  replication factors, and active replication servers.
- **Live delivery:** Pub/Sub nodes use SecureCyclon peer sampling, Navigation,
  and topic-specific Dissemination overlays over Netty connections.
- **Persistence:** publishing nodes send signed events to replication servers;
  deterministic placement, repair, and release maintain the requested replicas.
- **Observation:** scenario tooling runs workloads and faults, samples the
  system, and produces analysis-ready datasets and summaries.

Event payloads are not written to Cardano. Start with the
[plain-language system diagram](docs/architecture/diagrams/system-overview.svg)
or the [architecture guide](docs/architecture/README.md) for the complete flow.

## Prerequisites

- JDK 21.
- Docker with Docker Compose and the Linux container engine.
- Bash (`Git Bash` or WSL on Windows) for repository scripts.
- Node.js 18 or newer for testbed generation and experiment orchestration.

Cardano and Aiken versions are pinned by the repository. The testbed scripts
use locally installed Cardano tools when available and otherwise use Docker.

## Build and test

From Bash:

```bash
./gradlew --gradle-user-home .gradle-user-home test
```

From Windows PowerShell:

```powershell
.\gradlew.bat -g .gradle-user-home test
```

Dependencies are declared once in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). No system Gradle
installation is required.

## Run the integrated testbed

Run lifecycle and experiment commands from Bash at the repository root.

```bash
# First start; bootstraps Cardano, deploys registries, and creates the topology.
./scripts/testbed/up.sh --nodes 3

# Inspect processes and API health, or follow logs.
./scripts/testbed/status.sh
./scripts/testbed/logs.sh

# Stop containers while retaining identities, server data, and results.
./scripts/testbed/down.sh
```

`up.sh` accepts 3-50 Pub/Sub nodes and reuses an existing compatible bootstrap.
The first run can take several minutes because it builds distributions and
images, initializes the devnet, funds operators, and submits registry
transactions.

Default host ports are:

| Service | Host ports | Purpose |
| --- | --- | --- |
| Pub/Sub transport | `7001...` | Node-to-node Netty protocol |
| Pub/Sub control API | `8001...` | Publish, subscribe, recovery, and overlay views |
| Replication API | `8101`-`8103` | Storage, lookup, maintenance, and health |

To discard generated runtime state and Docker volumes while retaining
experiment results:

```bash
./scripts/testbed/reset.sh
```

This is intentionally destructive for local testbed/devnet state.

## Run experiments

Checked-in scenarios are under
[`ops/config/phase-0.9/scenarios`](ops/config/phase-0.9/scenarios). A run starts
the required topology, executes its workload and fault schedule, collects raw
telemetry, and generates Parquet plus derived summaries.

```bash
./scripts/experiments/run.sh ops/config/phase-0.9/scenarios/e1-scaling-10.yaml
./scripts/experiments/aggregate.sh results
```

Outputs use this layout:

```text
results/<scenarioId>/<runId>/
  scenario.yaml
  metadata.json
  logs/
  raw/       # authoritative JSONL observations
  parquet/   # canonical analysis datasets
  derived/   # JSON and CSV summaries

results/combined/  # cross-run aggregation
```

The eleven dataset schemas are documented in the
[telemetry data dictionary](results/data-dictionary.md).

## Acceptance

The current end-to-end acceptance flow builds and tests the repository, resets
local testbed state, runs a five-node fault/recovery scenario, aggregates its
telemetry, and writes evidence under
`implementation-reports/evidence/phase-0.9/`.

```bash
./scripts/acceptance/phase-0.9.sh
```

Because acceptance resets the devnet and testbed volumes, do not run it against
local state you need to keep. Historical acceptance entrypoints remain under
`scripts/acceptance/` for phase-specific regression checks.

## Repository map

| Path | Responsibility |
| --- | --- |
| [`apps/pubsub-node`](apps/pubsub-node) | Runnable node: configuration, control API, overlays, event validation/delivery, persistence and recovery integration |
| [`apps/replication-server`](apps/replication-server) | Runnable storage server: HTTP API, replica placement, failure detection, repair, and safe release |
| [`libs/peer-sampling`](libs/peer-sampling) | Peer-sampling contract and SecureCyclon implementation |
| [`libs/navigation`](libs/navigation), [`libs/dissemination`](libs/dissemination) | Topic-aware routing information and live-event overlay |
| [`libs/event-core`](libs/event-core), [`libs/protocol-core`](libs/protocol-core), [`libs/transport-netty`](libs/transport-netty) | Signed event model, wire messages/codec, identity, framing, sessions, and reconnection |
| [`libs/registry-cardano`](libs/registry-cardano) | Registry interfaces and Cardano CLI/ledger integration |
| [`libs/persistence-core`](libs/persistence-core) | Persistence records, DHT placement, local stores, replication client, membership, and recovery |
| [`libs/http-support`](libs/http-support) | Shared HTTP paths, methods, parameters, error codes, and JSON exchange helpers |
| [`contracts`](contracts) | Topic and replication registry Aiken contracts |
| [`tools/telemetry`](tools/telemetry) | JSONL validation, Parquet normalization, aggregation, summaries, and dictionary generation |
| [`ops/config`](ops/config) | Checked-in node, server, testbed, and scenario configuration |
| [`ops/infra/devnet`](ops/infra/devnet) | Local Cardano network definition and lifecycle tooling |
| [`scripts`](scripts) | Current registry, replication, event, testbed, experiment, and acceptance entrypoints |
| [`specs`](specs), [`implementation-reports`](implementation-reports) | Phase requirements, implementation decisions, and acceptance reports |

## Configuration and generated state

- [`ops/config/phase-0.9/testbed.yaml`](ops/config/phase-0.9/testbed.yaml) is the
  source configuration for generated topologies.
- `.tools/phase-0.9/` contains generated Compose and per-process configuration;
  do not edit it as source.
- `ops/infra/devnet/runtime/`, `state/`, and `keys/` contain generated local
  Cardano state and credentials. They are ignored by Git.
- `results/<scenarioId>/` and acceptance evidence are generated and ignored;
  `results/data-dictionary.md` is the checked-in schema reference.
- Pub/Sub identities and replication data live in Docker volumes so ordinary
  `down`/`up` cycles retain them.

## Where to read next

- [Architecture index and diagrams](docs/architecture/README.md) - conceptual
  model, internals, deployment, data flow, timing, and sequences.
- [Complete script reference](documentation/scripts.md) - every lifecycle,
  registry, event, experiment, and historical acceptance command.
- [Phase 0.9 specification](specs/phase-0.9.md) and
  [implementation report](implementation-reports/phase-0.9-implementation-report.md) -
  requirements, scope, and validation evidence.
- [Modernization plan](specs/modernization-refactor-plan.md) - current
  structural decisions and intentionally deferred migrations.
