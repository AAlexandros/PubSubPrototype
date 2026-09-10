# Behavior-preserving modernization plan

## Scope and guardrails

This plan modernizes structure without changing the Pub/Sub, registry,
replication, recovery, telemetry, HTTP, or script contracts. Public Java APIs,
CLI command names, HTTP paths and payloads, generated telemetry layouts, and
existing phase entrypoints remain stable.

## Audit findings

- No production Java type is confidently dead. Several historical phase shell
  entrypoints have no in-repository callers, but they may be external operator
  interfaces and are retained until usage can be confirmed.
- Dependency coordinates and versions were repeated in nearly every Gradle
  module.
- Windows, MSYS, WSL, Gradle, and Node path handling was duplicated across the
  registry, replication, testbed, devnet, experiment, and acceptance scripts.
- The experiment controller, topology generator, and acceptance scripts each
  carried local path conversion or YAML parsing implementations. Phase 0.9's
  eleven dataset identifiers were also duplicated.
- HTTP servers and the replication client repeated route names, HTTP methods,
  media types, error codes, suffix extraction, query parsing, and JSON response
  handling.
- `TelemetryTool` mixed command dispatch, JSONL validation, Avro/Parquet
  conversion, aggregation, summaries, and data-dictionary rendering.
- Remaining high-risk oversized types include `ReplicaMaintenanceManager`,
  `CardanoTransactionBuilder`, `DisseminationEngine`, `PubSubTransport`,
  `PeerSessionHandler`, and `NodeConfigLoader`. They need additional
  characterization tests before extraction.
- `ProtocolMessage` is a nullable multi-purpose record and `protocol-core`
  depends on domain implementations. Changing that shape would alter the wire
  contract and module architecture, so it is a migration rather than a safe
  refactor.

## Reviewable passes

| Pass | Current behavior | Structural improvement | Stability check |
| --- | --- | --- | --- |
| 1. Dependency catalog | Every module declares exact dependency strings and versions. | Use one Gradle version catalog, retaining the exact versions. | Resolve all projects and run the full Gradle test suite. |
| 2. Shell platform utility | Each script independently selects Gradle and converts MSYS/WSL paths. | Source prefixed functions from `scripts/lib/platform.sh`; distinguish a Windows Node runtime from Linux Node under WSL. | Bash syntax-check every migrated script and run existing script acceptance when infrastructure is available. |
| 3. Node utilities/contracts | Four scripts convert paths; two parse small YAML subsets; dataset and testbed strings repeat. | Export path/YAML helpers and frozen Phase 0.9 contracts from `scripts/lib`. | Run Node syntax checks and focused `node:test` parity cases. |
| 4. HTTP support | Servers and client embed matching string routes and duplicate exchange helpers. | Add `libs:http-support` with compiler-checked paths, methods, parameters, error codes, and JSON/URI helpers. | URI characterization tests plus all affected application/library tests. |
| 5. Telemetry responsibilities | One 265-line CLI class performs five different jobs. | Keep `TelemetryTool` as the stable facade and extract normalizer, aggregator, summary, dictionary, JSON, and layout collaborators. | Existing raw-preservation, Parquet magic, summary, and dictionary tests. |
| 6. Documentation | Refactor boundaries and intentional non-changes are implicit. | Record contracts, audit results, validation, and deferred migrations. | Documentation review and repository diff checks. |

## Separate migration tasks

The following should not be folded into behavior-preserving refactor commits:

1. Replace `ProtocolMessage` with a typed/sealed message hierarchy. First add
   golden JSON frames for every message type and mixed-version compatibility
   tests; then version the wire protocol.
2. Reverse the `protocol-core` dependencies on event/navigation/dissemination
   implementations. First document the desired dependency graph and introduce
   narrow DTO or port modules; migrate one protocol family at a time.
3. Move `ReplicationRegistryCli` out of the reusable `persistence-core`
   library. Preserve its application distribution and command syntax through a
   compatibility launcher before moving ownership.
4. Replace handwritten script YAML subsets with a full YAML package. Treat
   parser behavior, quoting, anchors, and dependency installation as an
   explicit tooling migration.
5. Upgrade Java, Gradle, Jackson, Netty, Parquet, Avro, Hadoop, SnakeYAML, or
   logging dependencies only in dedicated upgrade tasks with compatibility and
   vulnerability review.
6. Decompose the remaining runtime engines only after adding focused tests for
   replica placement/repair, transaction assembly, dissemination selection,
   transport lifecycle, peer session state, and configuration defaults.
7. Delete historical phase entrypoints only after confirming no CI, operator,
   or external automation depends on them and documenting replacement commands.
