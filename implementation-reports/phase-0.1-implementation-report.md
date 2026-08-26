# Implementation Report

## Task

Implement phase 0.1 of the Cardano Pub/Sub prototype from `specs/phase-0.1.md`: repository baseline, Java multi-module project, three-node transport with persistent identities and `PING`/`PONG`, runtime scripts, Docker Compose scaffolding, tests, and acceptance entry points.

## Outcome

Phase 0.1 is partially implemented.

Completed and validated:

- Java 21 Gradle multi-module baseline.
- `protocol-core`, `transport-netty`, and `pubsub-node` modules.
- Persistent Ed25519 identity creation/loading.
- `NodeId = SHA-256(publicKey)` as lowercase hex.
- Length-prefixed JSON protocol messages.
- `HELLO`, `HELLO_ACK`, `PING`, and `PONG`.
- Handshake validation, version validation, invalid node ID rejection, self-connection rejection, malformed-frame handling.
- Ping/Pong RTT tracking and timeout logging.
- Bounded exponential reconnect.
- Three-node YAML configuration.
- Docker Compose shape for phase 0.1.
- CI workflow.
- Acceptance script entry point.

Not fully completed:

- The Cardano devnet scripts expose the requested commands and runtime files, but genesis creation, real block production, and real funded Cardano identities are scaffolded placeholders. This should be scheduled as the next implementation task, because it depends on selecting and validating a concrete `cardano-cli`/node devnet recipe.

## Completed

- Promoted the imported Gradle wrapper to the repository root.
- Replaced the unrelated imported backend project with the requested prototype layout.
- Added Gradle Kotlin DSL root and module build files.
- Added Java protocol model and codec.
- Added identity persistence under an `identity.json` file.
- Added Netty TCP server/client transport with 4-byte big-endian length-prefix framing.
- Added lifecycle log events: `NODE_STARTED`, `PEER_CONNECTED`, `PEER_DISCONNECTED`, `PING_SENT`, `PONG_RECEIVED`, `PING_TIMEOUT`, and `RECONNECT_SCHEDULED`.
- Added duplicate-connection replacement without accidental reconnect storms.
- Added tests for node ID derivation, identity stability, message encode/decode, validation, reconnect backoff, malformed input, and three-node loopback full mesh.
- Added Dockerfiles, Compose files, runtime scripts, and acceptance script.

## Files Changed

- `README.md`
- `.gitignore`
- `.github/workflows/ci.yml`
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/wrapper/*`
- `libs/protocol-core/**`
- `libs/transport-netty/**`
- `apps/pubsub-node/**`
- `config/phase-0.1/node-1.yaml`
- `config/phase-0.1/node-2.yaml`
- `config/phase-0.1/node-3.yaml`
- `infra/devnet/**`
- `infra/phase-0.1/compose.yaml`
- `scripts/phase-0.1-up.sh`
- `scripts/phase-0.1-down.sh`
- `scripts/acceptance/phase-0.1.sh`
- `implementation-reports/phase-0.1-implementation-report.md`

## Validation

Ran successfully:

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .).Path + '\runtime\gradle-home'; .\gradlew.bat clean build
```

Result: `BUILD SUCCESSFUL`.

Ran successfully:

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .).Path + '\runtime\gradle-home'; .\gradlew.bat test
```

Result: `BUILD SUCCESSFUL`.

Ran successfully:

```powershell
docker compose -f infra/phase-0.1/compose.yaml config
docker compose -f infra/devnet/compose.yaml config
```

Result: both Compose files render successfully.

Not run to completion:

- `./scripts/phase-0.1-up.sh`
- `./scripts/acceptance/phase-0.1.sh`

Reason: the Cardano devnet genesis/funded-identity implementation is currently a placeholder and is expected to fail before a real Cardano devnet recipe is added.

## Notes

- Gradle validation used a workspace-local `GRADLE_USER_HOME` because sandboxed execution could not write to the default user Gradle cache.
- Docker access required elevated execution in this environment.
- The Cardano part should be the immediate follow-up: replace placeholder devnet `init.sh` with real single-block-producer genesis generation, create the four requested funded payment identities, and validate `status.sh`/`wait-healthy.sh` against advancing chain tips.
