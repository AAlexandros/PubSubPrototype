# Implementation Report

## Task

Implement `specs/phase-0.9.md`: integrate the local Cardano devnet, both
registries, Pub/Sub nodes, replication servers, experiment execution,
fault/recovery workloads, rich raw telemetry, Parquet normalization,
cross-run aggregation, architecture documentation, and acceptance evidence.

## Outcome

Implemented a reproducible local-only testbed which generates and runs 3–50
real Pub/Sub JVM containers (including the specified 3, 10, and 30-node E1
targets), three replication servers, and the existing local Cardano devnet.
One bootstrap path builds the Java distributions, funds local identities,
deploys both contracts, establishes the on-chain replication membership, and
waits for every process API before reporting readiness.

The experiment controller validates YAML scenarios, creates real on-chain
topics, subscribes live nodes, publishes real signed events at the requested
rate/payload size, executes scheduled node/server/factor faults, performs
offline recovery, samples all overlay and maintenance APIs, captures Docker
resources and structured runtime logs, and writes eleven correlated JSONL
datasets beneath the required scenario/run layout.

The new Java telemetry tool owns a typed catalog for every dataset. It rejects
missing datasets/required fields, writes real uncompressed Apache Parquet files,
generates per-run derived JSON and optional CSV summaries, combines repetitions
under `results/combined`, preserves all raw JSONL, and generates the checked-in
data dictionary. Unit tests verify Parquet header/footer magic and raw-input
immutability.

## Completed

- Added bootstrap, up/down/stop/restart/reset/status/log collection, workload,
  result collection, and acceptance entrypoints under `scripts/testbed/`.
- Added dynamic per-node config and Compose generation with stable host ports,
  persistent identities, registry mounts, and selectable node counts from 3 to
  50.
- Added Phase 0.9 configuration plus E1 scaling (3/10/30), E2 churn, E3
  multi-topic, E4 offline recovery, E5 server repair, E6 factor change, and a
  combined five-node acceptance scenario.
- Added `scripts/experiments/run.sh` and its dependency-free Node.js controller
  for real workloads, faults, overlay snapshots, runtime log ingestion,
  resource sampling, metadata, and stable cross-dataset identifiers.
- Added all required datasets: `runs`, `event_lifecycle`,
  `message_transmissions`, `overlay_edges`, `protocol_cycles`, `subscriptions`,
  `persistence_operations`, `replica_state`, `faults`, `resource_samples`, and
  `cardano_transactions`.
- Added `tools:telemetry`, backed by Avro/Apache Parquet, with catalog validation,
  lossless raw retention, per-run normalization, cross-run combination, derived
  dissemination/persistence/overlay/resource/control-plane summaries, and CSV
  export.
- Added the generated `results/data-dictionary.md` with type, unit, meaning,
  nullability, and source component for every field.
- Added D1–D10 Mermaid architecture, internal component, lifecycle, data model,
  deployment, and sequence diagrams under `docs/architecture/`.
- Added Phase 0.9 acceptance automation and evidence inventory/audit generation.
- Hardened WSL/MSYS path conversion and Windows replication-snapshot replacement
  for Docker Desktop acceptance runs.
- Made experiment stages visible while they run, removed the Gradle daemon from
  the normalization/aggregation path, reused current Parquet outputs during
  aggregation, and added acceptance resume support for completed raw runs.
- Made scheduled fault actions awaitable and required offline recovery to
  deliver at least one missed event instead of treating an empty HTTP 200
  recovery result as success.
- Updated the repository README and complete script reference.

## Files Changed

| Area | Files and purpose |
| --- | --- |
| Testbed | `scripts/testbed/*`, `ops/infra/phase-0.9/README.md`, and generated-runtime conventions |
| Configuration | `ops/config/phase-0.9/testbed.yaml` and nine scenario YAML files |
| Experiments | `scripts/experiments/run.sh`, `run.mjs`, and `aggregate.sh` |
| Telemetry | New `tools/telemetry` Gradle application, typed catalog, Parquet writer, summaries, dictionary generator, and tests |
| Results | `results/.gitkeep`, `results/data-dictionary.md`, and ignored per-run/combined output policy |
| Architecture | `docs/architecture/README.md`, seven diagram documents, and `diagrams/README.md` |
| Acceptance | `scripts/acceptance/phase-0.9.sh` and `phase-0.9.mjs` |
| Documentation | `README.md`, `documentation/scripts.md`, and this report |

## Validation

- Clean `gradlew.bat -g .gradle-user-home test`: passed all 70 actionable tasks,
  including the two new telemetry tests.
- Telemetry tests created and validated all eleven Parquet datasets, checked
  both `PAR1` boundaries, generated derived summaries, and confirmed raw JSONL
  was unchanged.
- `node --check` passed for the topology generator, experiment controller, and
  acceptance auditor.
- WSL `bash -n` passed for every new shell entrypoint.
- A generated five-node Compose topology passed `docker compose config --quiet`.
- Clean end-to-end `scripts/acceptance/phase-0.9.sh`: passed with five Pub/Sub
  nodes, three replication servers, scheduled node/server faults, real offline
  recovery, all eleven raw and Parquet datasets, aggregation, and the evidence
  audit. The accepted run contains 24 published events, 724 lifecycle
  observations, 253 transmissions, 592 overlay edges, and 1,338 protocol-cycle
  observations; delivery coverage and delivery hit ratio are both 1.0.
- `git diff --check` passed apart from the repository's existing Windows
  LF-to-CRLF notices.

## Notes

- Raw JSONL remains authoritative; Parquet and summaries can be regenerated at
  any time with `scripts/experiments/aggregate.sh`.
- The observer correlates live control APIs, Docker resource counters, Cardano
  CLI operations, and the components' structured key/value runtime logs. It
  does not replace or simulate protocol behavior.
- Generated Compose/config state lives under ignored `.tools/phase-0.9/`.
  Large run output lives under ignored `results/<scenarioId>/<runId>/`; only
  the schema dictionary and placeholder are versioned.
- `scripts/testbed/reset.sh` intentionally removes testbed volumes and generated
  devnet/runtime state, but retains experiment results.
