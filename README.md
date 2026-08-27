# Cardano Pub/Sub Prototype

Java 21 prototype for a Cardano-backed Pub/Sub network. Phase 0.1 is implemented and validated: three local Pub/Sub nodes form a full mesh, exchange `HELLO`, `PING`, and `PONG` messages over length-prefixed JSON frames, persist Ed25519 node identities, and run alongside a local Cardano devnet with funded test identities.

## Repository Layout

- `apps/pubsub-node/`: runnable Pub/Sub node application and Docker image.
- `libs/protocol-core/`: protocol messages, JSON codec, node identity storage, and `NodeId` derivation.
- `libs/transport-netty/`: Netty transport, peer sessions, framing, reconnection, and integration tests.
- `ops/config/phase-0.1/`: local three-node runtime configuration.
- `ops/infra/devnet/`: Cardano devnet Docker Compose setup, pinned tool versions, and lifecycle scripts.
- `ops/infra/phase-0.1/`: Docker Compose topology for Cardano plus the three Pub/Sub nodes.
- `ops/scripts/`: phase runtime and acceptance entrypoints.
- `specs/`: source specifications and phase task notes.
- `implementation-reports/`: implementation reports and acceptance evidence.

## Requirements

- Java 21, or a JDK toolchain Gradle can resolve.
- Docker with Docker Compose.
- Bash for the `ops/scripts` and `ops/infra/devnet/scripts` entrypoints.

The Cardano tools are pinned in `ops/infra/devnet/versions.env`. If `cardano-testnet`, `cardano-node`, and `cardano-cli` are available on `PATH`, the scripts can use them directly. Otherwise Docker builds and uses the pinned `pubsub-cardano-testnet:11.0.1` runtime image.

## Build And Test

```bash
./gradlew clean build
./gradlew test
```

On Windows PowerShell:

```powershell
.\gradlew.bat clean build
.\gradlew.bat test
```

## Run Phase 0.1 Locally

Start the Cardano devnet, wait for health, fund the four phase identities, and start the three Pub/Sub nodes:

```bash
./ops/scripts/phase-0.1-up.sh
```

Stop the Pub/Sub nodes and devnet:

```bash
./ops/scripts/phase-0.1-down.sh
```

Follow the three Pub/Sub node logs:

```bash
./ops/scripts/phase-0.1-logs.sh
```

The Java nodes persist Ed25519 identities in per-node Docker volumes. `NodeId` is derived as lowercase hex `SHA-256(publicKey)`.

## Acceptance

Run the Phase 0.1 acceptance flow:

```bash
./ops/scripts/acceptance/phase-0.1.sh
```

The default acceptance soak is 30 seconds. Override it when you want a longer run:

```bash
PHASE_0_1_SOAK_SECONDS=600 ./ops/scripts/acceptance/phase-0.1.sh
```

The acceptance run verifies:

- Gradle clean build and tests.
- Cardano devnet reset, start, health, chain-tip advancement, and clean restart.
- Funding for `registry-deployer`, `node-1`, `node-2`, and `node-3`.
- Three Pub/Sub nodes reaching full mesh.
- Continuous `PING` / `PONG` exchange with RTT logging.
- Detection of a stopped node and automatic reconnection after restart.

Successful acceptance evidence is written to `implementation-reports/evidence/phase-0.1/`.

## Cardano Devnet

The devnet integration uses `cardano-testnet` rather than a custom chain implementation. Runtime values for Java/Cardano integration are exported to:

```text
ops/infra/devnet/runtime/network.env
```

That file includes:

- `CARDANO_NODE_SOCKET_PATH`
- `CARDANO_NETWORK_MAGIC`
- `REGISTRY_DEPLOYER_ADDRESS`
- `NODE_1_CARDANO_ADDRESS`
- `NODE_2_CARDANO_ADDRESS`
- `NODE_3_CARDANO_ADDRESS`

`fund-identities.sh` locates a generated testnet funding UTxO, submits real funding transactions, waits for confirmation, and fails if the target identities are not funded.

## Current Status

Phase 0.1.2 completed Phase 0.1 final integration and acceptance. The latest implementation report is `implementation-reports/phase-0.1.2-implementation-report.md`.
