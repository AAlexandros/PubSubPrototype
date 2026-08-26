# Cardano Pub/Sub Prototype

Phase 0.1 establishes a Java 21 multi-module baseline, local runtime scripts, a Cardano devnet scaffold, and three Pub/Sub nodes that exchange `HELLO`, `PING`, and `PONG` messages over length-prefixed JSON frames.

Repository layout:

- `apps/`: runnable applications.
- `libs/`: shared Java modules.
- `ops/`: local scripts, Docker Compose files, devnet tooling, and runtime configuration.
- `specs/`: source specifications.
- `implementation-reports/`: phase reports and generated acceptance evidence.

## Build

```bash
./gradlew clean build
./gradlew test
```

On Windows:

```powershell
.\gradlew.bat clean build
.\gradlew.bat test
```

## Phase 0.1 Runtime

```bash
./ops/scripts/phase-0.1-up.sh
./ops/scripts/acceptance/phase-0.1.sh
./ops/scripts/phase-0.1-down.sh
```

The Java nodes persist their Ed25519 identities in per-node Docker volumes. `NodeId` is derived as lowercase hex `SHA-256(publicKey)`.

## Cardano Devnet

The devnet integration is centered on `cardano-testnet`, pinned in `ops/infra/devnet/versions.env`. If `cardano-testnet`, `cardano-node`, and `cardano-cli` are on `PATH`, the scripts use them directly. Otherwise Docker Compose builds `ops/infra/devnet/Dockerfile`, which installs the pinned tools from the upstream `cardano-node` flake.

Runtime values for later Java/Cardano integration are exported to:

```text
ops/infra/devnet/runtime/network.env
```

That file includes the node socket path, network magic, and the four phase Cardano addresses.

## Current Boundary

The transport baseline is implemented. Cardano devnet scripts generate and verify the four required identities. Funding verification is real: `fund-identities.sh` fails until the running local testnet provides funded UTxOs for those addresses.
