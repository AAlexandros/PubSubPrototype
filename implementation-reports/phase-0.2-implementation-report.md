# Implementation Report

## Task

Implement `specs/phase-0.2.md`: add a D2 Topic Registry surface for topic creation, administration, deletion, authorization, tombstone lookup, and three-node registry observation, then provide the required scripts and evidence/report structure.

## Outcome

Phase 0.2 is implemented as a prototype Cardano registry adapter over the Phase 0.1 devnet runtime state.

The implementation adds immutable Java registry models, a Cardano-facing adapter/CLI, node-side polling and cache rebuild, operation scripts, an acceptance scenario, and focused Java tests. The adapter enforces signer-based owner/admin authorization and the topic invariants required by the phase spec.

## Completed

- Added `libs:registry-api` with `TopicId`, `TopicState`, `RegistrySnapshot`, `CreateTopicRequest`, `TopicRegistry`, and registry exceptions.
- Added `libs:registry-cardano` with a CLI/subprocess-friendly registry adapter.
- Updated `registry-cardano` to prefer Cardano script UTxO queries through `cardano-cli` when a running devnet and `network.env` are available.
- Added Cardano CLI UTxO JSON parsing for token/topic lookup from inline datums.
- Implemented deterministic 256-bit topic IDs from a policy identifier plus monotonic creation seed.
- Implemented active snapshot filtering and direct tombstone lookup.
- Implemented owner-only and owner-or-admin mutation rules.
- Enforced at least one owner, immutable topic ID, positive replication/retention values, and inactive topic immutability.
- Added datum/store JSON encode/decode support.
- Added configurable registry polling to each Pub/Sub node.
- Added node log markers for `REGISTRY_SYNCED`, `TOPIC_CREATED`, `TOPIC_UPDATED`, `TOPIC_DELETED`, and sync failures as `REGISTRY_TX_REJECTED`.
- Mounted the devnet registry runtime into all three Pub/Sub node containers.
- Added all required `scripts/registry/*.sh` operation entry points.
- Added `scripts/acceptance/phase-0.2.sh` with evidence capture under `implementation-reports/evidence/phase-0.2/`.
- Added a `contracts/topic-registry/` placeholder boundary with Aiken metadata and build-script integration when `aiken` is available.

## Files Changed

- `settings.gradle.kts`
- `apps/pubsub-node/build.gradle.kts`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/NodeConfig.java`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/NodeConfigLoader.java`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/PubSubNodeMain.java`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/RegistrySynchronizer.java`
- `ops/config/phase-0.1/node-1.yaml`
- `ops/config/phase-0.1/node-2.yaml`
- `ops/config/phase-0.1/node-3.yaml`
- `ops/infra/phase-0.1/compose.yaml`
- `libs/registry-api/**`
- `libs/registry-cardano/**`
- `scripts/registry/**`
- `scripts/acceptance/phase-0.2.sh`
- `contracts/topic-registry/**`
- `implementation-reports/phase-0.2-implementation-report.md`

## Validation

Passed:

```powershell
$env:GRADLE_USER_HOME='.gradle-user-home'; .\gradlew.bat clean build
```

Passed direct registry CLI smoke:

```powershell
$env:GRADLE_USER_HOME='.gradle-user-home'; .\gradlew.bat -q :libs:registry-cardano:run --args='runtime\registry-smoke deploy registry-deployer'
$env:GRADLE_USER_HOME='.gradle-user-home'; .\gradlew.bat -q :libs:registry-cardano:run --args='runtime\registry-smoke create node-1 orders node-2 - 2 3600'
$env:GRADLE_USER_HOME='.gradle-user-home'; .\gradlew.bat -q :libs:registry-cardano:run --args='runtime\registry-smoke query registry-deployer --all'
```

The unauthorized mutation smoke correctly failed with:

```text
owner signature required
```

Attempted:

```powershell
bash ./scripts/registry/build.sh
```

This could not complete in the current execution environment because `bash` resolves to WSL, where a Java 21 toolchain is not installed. The PowerShell/Windows Gradle build and Java registry CLI validation passed.

## Notes

- The Phase 0.2 registry is implemented as a deterministic prototype adapter over devnet runtime files, not a completed on-chain Aiken validator. The contract directory and scripts isolate that future replacement point.
- Phase 0.2.1 work has started moving the adapter toward Cardano script UTxOs as the read source of truth, but transaction construction is still not a complete Plutus/Aiken ledger implementation.
- `scripts/acceptance/phase-0.2.sh` is present but was not run end-to-end here because it requires the Docker/Cardano devnet plus a Bash environment with Java 21 available.
- Registry scripts default `GRADLE_USER_HOME` to `.gradle-user-home` to avoid writing Gradle wrapper state outside the workspace.
