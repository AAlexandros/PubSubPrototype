# Implementation Report

## Task

Implement Phase 0.11: durable subscriber-local event history, automatic restart catch-up, local read APIs and CLI commands, and integrated acceptance coverage.

## Outcome

Phase 0.11 is implemented and the integrated Docker acceptance run passed on 31 October 2026. The run exercised real Cardano devnet, registries, replication servers, and pub-sub nodes. Node 3 kept one live event across a restart, automatically fetched and stored three events published while offline, and kept all four across a second restart. Its delivery cursor reached sequence 3. The runner saved final evidence, stopped and removed both Compose projects' containers and networks, and exited successfully.

## Completed

- Added a node-local JSON event store on the node's persistent identity volume, with atomic writes, deterministic topic/publisher/sequence paths, event-ID index reconstruction, conflict rejection, bounded ordered listing, and explicit corrupt-file failure.
- Reconciled delivery cursors from contiguous stored event files on startup. Live, local-published, and recovered subscriber events use the same store-before-cursor operation; forwarding nodes do not save subscriber history unless subscribed.
- Started background catch-up for subscriptions restored at process startup, using existing replication publisher progress and event lookup, existing validation, bounded fetch windows, and per-publisher gap blocking. Query failures from every replication server now surface as errors.
- Added local HTTP reads for one event, publisher sequence, event pages, and topic history, plus observational catch-up status. Added `events`, `event`, and `catch-up-status` CLI commands; `recover` remains diagnostic compatibility only.
- Updated the node command reference and README. Added a Phase 0.11 acceptance entrypoint that uses real devnet, registry, replication, and node components and captures local event, restart, catch-up, cursor, log, and inventory evidence without invoking recovery.
- Updated the three Phase 0.7 node YAML files to the current registry config schema and added a test that loads those exact files.
- Updated Phase 0.7 Compose to pass Cardano network settings and mount the devnet socket into pub-sub nodes using the `HOST` backend.
- Added EXIT cleanup to the Phase 0.11 acceptance runner so it saves final logs, stops and removes test/devnet containers and networks, records cleanup outcome, and exits with failure if cleanup fails.
- Updated the startup wait to match the current `SAMPLING_STARTED` log marker.
- Routed the event publish and inject wrappers through the shared Node path helper for Git Bash and Windows Node compatibility.
- Added one bounded devnet restart for the observed Cardano block-production timeout, preserving the failed attempt's log before resetting its generated state.
- Extended the existing experiment event lifecycle dataset with local-store and catch-up stages plus a delivery source field, and updated the data dictionary.

## Files Changed

- `libs/src/main/java/org/pubsub/prototype/persistence/adapter/NodeEventStore.java`, `DeliveryStateStore.java`, and `libs/src/main/java/org/pubsub/prototype/persistence/core/ReplicationServerClient.java`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/service/EventStorageService.java`, `EventProcessingService.java`, `bootstrap/PubSubNodeApplication.java`, and `adapter/http/NodeControlHttpAdapter.java`
- `scripts/pubsub-node.mjs` and `scripts/acceptance/phase-0.11.mjs`
- `scripts/lib/platform.sh` and `scripts/acceptance/phase-0.7.mjs` for robust Windows command output and server ID validation
- `ops/infra/phase-0.7/compose.yaml` for pub-sub Cardano environment and socket access
- `ops/config/phase-0.7/node-1.yaml`, `node-2.yaml`, and `node-3.yaml` for the current node configuration schema
- `scripts/acceptance/phase-0.11.sh` for unconditional teardown and final-result reporting
- `scripts/events/publish.sh` and `scripts/events/inject.sh` for portable Node script invocation
- `libs/src/test/java/org/pubsub/prototype/persistence/adapter/NodeEventStoreTest.java` and node recovery/HTTP route/config tests, including `apps/pubsub-node/src/test/java/org/pubsub/prototype/node/NodeConfigTest.java`
- `README.md` and `documentation/node-cli.md`
- `scripts/experiments/run.mjs`, `tools/telemetry/src/main/java/org/pubsub/prototype/telemetry/DatasetCatalog.java`, and `results/data-dictionary.md`
- `implementation-reports/phase-0.11-implementation-report.md`

## Validation

- `gradlew.bat -g .gradle-user-home test --no-daemon --quiet`: passed.
- `node --check` for both changed JavaScript entrypoints and `git diff --check`: passed.
- `scripts/acceptance/phase-0.11.sh`: completed successfully in Git Bash, including its `clean test` gate and container teardown.
- `DEBUG=release scripts/replication/server-id.sh ...` through Git Bash: emitted exactly one 64-character server ID.
- Regenerated Phase 0.7 server YAML with the updated helper and confirmed it emits `replicationRuntimeDir`, `pollIntervalMs`, and `maximumAgeMs` with no obsolete `membershipPath`.
- Focused tests cover complete envelope reload, lookup, pagination, conflicts, corruption, cursor reconciliation, persistence failure, unavailable sequence gaps, automatic scheduling, unsubscribed isolation, and HTTP local reads.
- `:apps:pubsub-node:test --tests org.pubsub.prototype.node.NodeConfigTest`: passed after adding coverage for all three Phase 0.7 deployment configs.
- `docker compose ... config --quiet` for Phase 0.7 Compose: passed after the environment and socket changes.
- Integrated acceptance: **passed**. `final-result.txt` reads `Phase 0.11 acceptance passed; containers stopped and removed`; `acceptance-output.log` shows `BUILD SUCCESSFUL` and completion of all checks.
- Event history: `live-events.json` and `first-restart-events.json` each contain one event; `caught-up-events.json` and `second-restart-events.json` each contain four, with no extra page or duplicate event IDs.
- Automatic catch-up: `automatic-catch-up-status.json` reports `COMPLETED`, with three fetched and stored, zero duplicates, unavailable events, or failures. `caught-up-delivery-state.json` records `lastDeliveredSequenceNumber: 3`.
- Local read and persistence evidence: the CLI listing contains four events, a missing event returns `not_found`, and the on-disk inventory contains four files both before and after the second restart. `no-explicit-recovery.txt` records that no diagnostic recover action was invoked.
- Evidence is in `implementation-reports/evidence/phase-0.11/`.

## Notes

- Local files are stored at `<delivery-state directory>/events`, which is `/data/identity/events` in the testbed. They have no automatic pruning.
- Catch-up queries publisher progress without a topic-wide timestamp filter because that filter could hide a lagging publisher when another publisher has a newer event.
- The acceptance script resets the local Cardano devnet and its test volumes; run it only when that reset is intended.
- The successful run removed its containers and networks; named volumes remain until the next acceptance run resets them. The bounded devnet startup retry was not needed in this run.
