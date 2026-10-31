# Implementation Report

## Task

Implement Phase 0.10 direct Cardano registry observation for Pub/Sub nodes and replication servers, remove the old shared-cache authority, and supply acceptance evidence.

## Outcome

Both applications now query their own Topic and Replication Registry views. Successful queries publish complete in-memory views; failed or invalid queries preserve the last valid view. Registry-dependent operations require a first successful observation and a view no older than the configured maximum age. The generated testbed uses `HOST` queries, read-only Cardano sockets, and trusted deployment metadata file mounts. The legacy `CACHE_ONLY` runner and file-backed replication registry were removed.

The prototype intentionally reuses each replication server's registered Cardano payment key to sign authenticated HTTP requests, prioritizing simplicity and keeping the server and request identities aligned. An earlier expanded Phase 0.10 acceptance passed, including the integrated persistence and missed-event recovery regression; it predates the validator hardening described below.

## Completed

- Added per-instance atomic topic and membership views with immediate startup polling followed by fixed-interval polling, `NOT_LOADED`/`FRESH`/`STALE` status, maximum-age enforcement, and classified observation logs.
- Made topic and replication readers require successful live Cardano queries. Confirmed empty UTxO results are valid; missing queries, malformed data, duplicate states, unexpected addresses, and mismatched topic tokens fail the poll.
- Closed a topic-registry poisoning path: mint and spend now share one Aiken validator and script hash. Minting requires the topic token to enter that script with a valid inline datum; spending preserves the token and validates each replacement datum. These checks cover unique actors within each role list, expected key-hash lengths, valid UTF-8 names, and Java-supported replication/retention ranges. The reader ignores script-address UTxOs without the configured registry policy token and checks that each topic datum matches exactly one registry token, with one state UTxO per topic ID. The redundant Java actor-list scans were removed.
- Removed disk-cache fallback, shared registry snapshot reads, cache-only backend, and snapshot writes from registry readers.
- Updated the integrated testbed generator, Pub/Sub image, socket and deployment metadata mounts, polling settings, architecture diagrams, and CLI documentation.
- Recorded the prototype decision to reuse registered Cardano payment keys for replication-server HTTP request signing.
- Routed the replication server's epoch queries through the configured runtime registry backend and shared Cardano command runner factory, removing its separate host-only network configuration.
- Aligned Java registry list additions with the Aiken contract's prepend order after the acceptance run exposed a rejected publisher update.
- Removed a global MSYS path-conversion override that caused the event CLI to resolve repository paths as `C:\c\Users\...` on Windows.
- Restored the MSYS helper function used by the standalone devnet scripts while keeping its invocation out of the long-lived testbed acceptance shell.
- Fixed the replication server ID helper to resolve verification keys to absolute paths before passing them to the Gradle CLI.
- Added `/v1/ops/registry` diagnostics and a Phase 0.10 acceptance runner covering on-chain changes, publisher revocation, membership, cache tampering, startup/outage/staleness, and scaled polling.
- Moved the duplicated registry status response assembly out of both application bootstraps into a shared `RegistryDiagnostics` helper. The operations endpoint remains available for readiness inspection and the acceptance runner; its last observed values are never used to authorize work.
- Replaced the node-only `TopicRegistrySyncService` with direct `RegistryObserver<List<TopicState>>` usage in both applications. The observer records read time for topic and replication data; topic state is represented by an ordered list, without a separate timestamp wrapper.
- Extended the Phase 0.10 acceptance with an integrated persistence regression that verifies initial replica placement, availability during a responsible replication-server outage, replica repair after rejoin, exact ordered offline-subscriber recovery without duplicates, and live dissemination after recovery. It asserts direct `HOST` registry configuration and the absence of shared registry-cache mounts/settings.
- Changed the local devnet epoch length from 500 to 10,000 slots after live logs showed slot calculation failing at the first epoch boundary during the longer acceptance run.
- Changed the outage scenario to temporarily hide the shared Cardano socket while the chain keeps running, then restore it for recovery; the exit trap restores the socket after a failed check.
- Made devnet startup reuse an already running Cardano container during the six-node scale step. A stopped container with existing testnet genesis output now gives a reset instruction instead of attempting to regenerate genesis in that directory.
- Completed the initial live acceptance before adding persistence regression coverage: registry mutations, tampered snapshot files, outage freshness states, recovery, and scaling to six Pub/Sub nodes passed. Nine processes reported fresh registry views after scaling, with 73 poll observations captured.

## Files Changed

- Shared registry observation and Cardano readers: `libs/src/main/java/org/pubsub/prototype/registry/` and `libs/src/main/java/org/pubsub/prototype/cardano/cli/`.
- Application wiring, configuration, and HTTP diagnostics: `apps/pubsub-node/` and `apps/replication-server/`.
- Testbed and acceptance: `scripts/testbed/`, `scripts/replication/server-id.sh`, `scripts/acceptance/phase-0.7.mjs`, `scripts/acceptance/phase-0.10.*`, `ops/config/phase-0.9/testbed.yaml`, and `ops/infra/devnet/`.
- Topic contract and build support: `contracts/topic-registry/validators/topic_registry.ak`, `contracts/topic-registry/README.md`, `scripts/registry/export-aiken-scripts.mjs`, `scripts/registry/aiken-common.sh`, `scripts/registry/build-aiken-docker.sh`, `scripts/registry/build.sh`, and `scripts/replication/build.sh`.
- Phase 0.10 requirements: `specs/phase-0.10.md`.
- Documentation: `docs/architecture/`, `documentation/cardano-cli.md`, and `documentation/phase-0.10-registry-observation.md`.

## Validation

- All affected Java suites passed: `:libs:test`, `:apps:pubsub-node:test`, and `:apps:replication-server:test`.
- JavaScript syntax checks and Bash syntax check passed. The generator produced the expected live-query configuration.
- The server ID helper passed a Bash syntax check and returned an ID from the generated node-1 verification key when called with the same relative path used by bootstrap.
- Earlier user-run attempts exposed a Java/Aiken registry list-order mismatch, path-conversion errors, and a 500-slot devnet epoch boundary. Those issues were fixed during iteration; the registry-focused acceptance then passed. See `implementation-reports/evidence/phase-0.10/validation.md` for the attempt history and prior evidence.
- The earlier registry-focused acceptance passed before the integrated persistence scenario was added. It verified the outage freshness states and six-node scaling, with 73 registry poll observations.
- The first expanded acceptance run failed on its first published event: all replication servers rejected persistence because `CardanoCliEpochProvider` expected only `epoch` and `syncProgress`, while the configured Cardano CLI also returned `block`, `era`, `hash`, `slot`, `slotInEpoch`, and `slotsToEpochEnd`. The epoch parser now ignores unrelated tip fields, and a focused test using the observed CLI response passed.
- The next acceptance attempt stopped before registry deployment because Git Bash could not connect to the WSL service (`Wsl/Service/0x8007274c`) while building the Topic Registry. On Windows, both Aiken build scripts now prefer the pinned native `npx` package when available and retain WSL as a fallback. Both scripts passed Bash syntax checks.
- The following attempt ran Aiken through the Git Bash `npx` shim, which terminated with `Illegal instruction` after contract compilation began. A subsequent attempt reached an interactive `cmd.exe` prompt because Git Bash converted `/c` into a path. Scoping MSYS argument conversion allowed the Windows launcher to return `aiken v1.1.23+8949565`, but compilation still failed on the next run.
- A later attempt reported exit code 132 during native Windows Aiken compilation. A focused check launched outside Git Bash also exited after the same compilation lines; WSL timed out. On this ARM64 host, the Linux ARM64 compiler succeeds in Docker. Both registry build scripts now use a shared Docker runner on Git Bash; its focused Topic Registry check and both blueprint builds passed.
- Another expanded run stored its first event on all three replication servers but timed out waiting for live delivery to node 2. Logs showed node 1 had zero dissemination targets after the acceptance restarted it: the generated node-1 config had no bootstrap peer. The testbed now gives node 1 node 2 as a seed and waits for all three topic dissemination views to have peers before publishing. JavaScript and Bash syntax checks and `git diff --check` passed for this fix.
- The completed expanded run passed (`evidence/phase-0.10/final-result.txt`, 30 October 2026). It reached the six-node scale check with all nine processes reporting fresh registry views; `query-load.json` records 72 poll observations, eight per process. The integrated scenario verified replica placement, server outage and repair, and offline-subscriber recovery. `node-3-recovery-result.json` reports three missing and three delivered events, with none unavailable; the script also asserted exact order, no duplicates, and subsequent live dissemination.
- The topic datum-invariant hardening described above was added after that acceptance run. Combining mint and spend changes the script hash, policy ID, and validator address, so it requires a fresh registry deployment. The full Phase 0.10 acceptance has not been rerun for these latest changes; run `scripts/acceptance/phase-0.10.sh` to validate them.
- A focused Aiken `check --skip-tests` and `build` passed for the combined validator. Blueprint export succeeded and confirmed identical compiled code and hash for the mint and spend handlers. The focused `CardanoRegistryObservationTest` passed after updating its unrelated-UTxO expectation; `node --check` passed for the exporter.
- After aligning epoch queries with the runtime registry backend, `:apps:replication-server:compileTestJava --offline` passed. The container acceptance script has not been rerun for this change.
- After extracting the shared registry diagnostics helper, `:apps:pubsub-node:compileJava :apps:replication-server:compileJava --offline` passed. The response format and acceptance script were left unchanged.
- After removing the topic-specific view wrapper, `:apps:pubsub-node:compileJava :apps:replication-server:compileJava --offline` passed. The container acceptance script has not been rerun for this simplification.

## Notes

The testbed uses a 5 s poll interval, 15 s CLI timeout, and 30 s maximum age. The CLI trusts the configured Cardano node and its socket; the Java processes do not independently verify the chain. The current UTxO query does not provide a chain position, so observation logs mark it unavailable.

The earlier Phase 0.7 and 0.8 persistence checks remain useful standalone coverage. The expanded Phase 0.10 acceptance repeats the server-outage, replica-repair, and exact missed-event recovery path against direct Cardano polling. After capturing evidence, the acceptance script calls the existing testbed reset with `--no-init` on success or failure, removing its containers, volumes, keys, and generated runtime state while retaining the report evidence. Cleanup failures make the run fail. This cleanup behavior was added after the passing run and has not yet been exercised by a full acceptance rerun. The containers, volumes, and generated state left by that earlier run were removed manually; both Compose projects now have zero containers and volumes. `git diff --check` passed for the cleanup edits; a final Bash syntax check could not run because Git Bash execution approval was rejected.
