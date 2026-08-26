# Implementation Report

## Task

Implement phase 0.1.1 from `implementation-reports/phase-0.1.1-missing-requirements.md`: replace placeholder Cardano devnet automation with `cardano-testnet` integration, generate and verify the four local Cardano identities, and strengthen the full phase acceptance script.

## Outcome

Phase 0.1.1 is implemented with one explicit operational boundary:

- The repository now integrates `cardano-testnet` and pins Cardano tooling versions.
- Devnet scripts now generate real Cardano payment keys, verification keys, and testnet addresses for `registry-deployer`, `node-1`, `node-2`, and `node-3`.
- Runtime configuration is exported to `infra/devnet/runtime/network.env`.
- Health checks query the chain tip and require slot advancement.
- Funding checks query UTxOs and fail if identities are not funded.
- The phase acceptance script now includes build/test, devnet reset/start/health, identity funding checks, full-mesh node validation, PING/PONG + RTT validation, a 10-minute soak, node restart/reconnect validation, and stop/reset/start validation.

The remaining operational boundary is funding: `cardano-testnet` must expose or be configured with a funded UTxO source for these four generated addresses. The scripts no longer fake this requirement.

## Completed

- Added `infra/devnet/versions.env` to pin:
  - `cardano-node`
  - `cardano-cli`
  - `cardano-testnet`
  - network magic `42`
- Added `infra/devnet/Dockerfile` and `infra/devnet/container/start-testnet.sh`.
- Reworked `infra/devnet/compose.yaml` to run the pinned `cardano-testnet` environment.
- Replaced placeholder devnet scripts with real automation:
  - `init.sh`
  - `start.sh`
  - `stop.sh`
  - `status.sh`
  - `reset.sh`
  - `wait-healthy.sh`
  - `fund-identities.sh`
- Updated `scripts/phase-0.1-up.sh` and `scripts/phase-0.1-down.sh`.
- Reworked `scripts/acceptance/phase-0.1.sh` for the complete phase scenario.
- Hardened duplicate Netty connection handling with deterministic node-ID ordering.
- Updated `README.md`.

## Files Changed

- `README.md`
- `infra/devnet/Dockerfile`
- `infra/devnet/versions.env`
- `infra/devnet/container/start-testnet.sh`
- `infra/devnet/compose.yaml`
- `infra/devnet/scripts/common.sh`
- `infra/devnet/scripts/init.sh`
- `infra/devnet/scripts/start.sh`
- `infra/devnet/scripts/stop.sh`
- `infra/devnet/scripts/status.sh`
- `infra/devnet/scripts/reset.sh`
- `infra/devnet/scripts/wait-healthy.sh`
- `infra/devnet/scripts/fund-identities.sh`
- `infra/phase-0.1/compose.yaml`
- `scripts/phase-0.1-up.sh`
- `scripts/phase-0.1-down.sh`
- `scripts/acceptance/phase-0.1.sh`
- `libs/transport-netty/src/main/java/org/pubsub/prototype/transport/PubSubTransport.java`
- `libs/transport-netty/src/main/java/org/pubsub/prototype/transport/PeerSessionHandler.java`
- `implementation-reports/phase-0.1.1-implementation-report.md`

## Validation

Passed:

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .).Path + '\runtime\gradle-home'; .\gradlew.bat clean build
```

Passed:

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .).Path + '\runtime\gradle-home'; .\gradlew.bat test
```

Passed:

```powershell
docker compose --env-file infra/devnet/versions.env -f infra/devnet/compose.yaml config
docker compose --env-file infra/devnet/versions.env -f infra/phase-0.1/compose.yaml config
```

Passed:

```powershell
docker run --rm -v "${PWD}:/work" -w /work bash:5.2 bash -n infra/devnet/scripts/common.sh infra/devnet/scripts/init.sh infra/devnet/scripts/start.sh infra/devnet/scripts/stop.sh infra/devnet/scripts/status.sh infra/devnet/scripts/reset.sh infra/devnet/scripts/wait-healthy.sh infra/devnet/scripts/fund-identities.sh scripts/phase-0.1-up.sh scripts/phase-0.1-down.sh scripts/acceptance/phase-0.1.sh
```

Partially validated:

```powershell
docker build --build-arg CARDANO_TESTNET_VERSION=11.0.1 -t pubsub-cardano-testnet:11.0.1 infra/devnet
```

The build reached the upstream `cardano-node` flake and the final Nix flags were accepted. The full image build was not run to completion because resolving/building Cardano artifacts can be long.

Not run to completion:

```bash
./scripts/acceptance/phase-0.1.sh
```

Reason: this requires a completed `cardano-testnet` runtime with funded identity UTxOs and a 10-minute soak.

## Notes

- `PHASE_0_1_SOAK_SECONDS` can be set to a smaller value for local smoke tests; the default is `600`.
- `fund-identities.sh` deliberately fails if balances are missing. This is required for correctness because later Cardano transactions must not assume phantom funds.
- The next practical task is to run the acceptance script in an environment with `cardano-testnet`, `cardano-node`, and `cardano-cli` already installed or with the Docker image fully built using the upstream binary caches.
