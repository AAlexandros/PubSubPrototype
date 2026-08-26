# Implementation Report

## Task

Implement `specs/phase-0.1.2.md`: complete the Cardano devnet integration, fund local phase identities from a real generated testnet UTxO, validate the three-node Pub/Sub prototype with smoke and full acceptance, and store evidence under `implementation-reports/evidence/phase-0.1/`.

## Outcome

Phase 0.1.2 is implemented and validated.

The 30-second smoke acceptance passed, followed by the full default Phase 0.1 acceptance run with a 600-second soak. Evidence was regenerated under `implementation-reports/evidence/phase-0.1/`.

## Completed

- Built a runnable `pubsub-cardano-testnet:11.0.1` image that includes `cardano-testnet`, `cardano-node`, and `cardano-cli` via the pinned upstream Cardano Nix flake.
- Fixed the devnet container entrypoint to use the absolute startup script path.
- Made the Cardano devnet deterministic enough for local acceptance by using three pool nodes and `CARDANO_TESTNET_ACTIVE_SLOTS_COEFF=1.0`.
- Added Windows/Git Bash Docker path handling for devnet scripts.
- Updated the devnet health probe so Docker-backed `cardano-cli` queries are not blocked by host-side Unix socket type checks.
- Implemented real identity funding from generated `cardano-testnet` UTxO keys.
- Updated UTxO parsing for Cardano CLI JSON output.
- Switched funding transaction creation to a deterministic raw transaction with explicit fee/change.
- Added a funding readiness delay for Cardano local transaction submission startup.
- Fixed the Pub/Sub node Docker image to copy the module `installDist` output.
- Fixed YAML config loading for the record-based `NodeConfig`.
- Adjusted restart acceptance to assert observable recovery rather than a brittle internal `RECONNECT_SCHEDULED` marker.
- Captured Cardano tool versions in acceptance evidence.

## Files Changed

- `apps/pubsub-node/Dockerfile`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/NodeConfigLoader.java`
- `ops/infra/devnet/Dockerfile`
- `ops/infra/devnet/container/start-testnet.sh`
- `ops/infra/devnet/scripts/common.sh`
- `ops/infra/devnet/scripts/fund-identities.sh`
- `ops/infra/devnet/scripts/wait-healthy.sh`
- `ops/infra/devnet/versions.env`
- `ops/scripts/acceptance/phase-0.1.sh`
- `ops/scripts/phase-0.1-up.sh`
- `ops/scripts/phase-0.1-down.sh`
- `implementation-reports/phase-0.1.2-implementation-report.md`

## Validation

Passed:

```powershell
docker build --build-arg CARDANO_TESTNET_VERSION=11.0.1 -t pubsub-cardano-testnet:11.0.1 ops/infra/devnet
```

Passed:

```powershell
PHASE_0_1_SOAK_SECONDS=30 ./ops/scripts/acceptance/phase-0.1.sh
```

Passed:

```powershell
./ops/scripts/acceptance/phase-0.1.sh
```

The full acceptance run performed:

- Gradle clean build plus `:apps:pubsub-node:installDist`.
- Cardano devnet reset/start/health check.
- Funding for `registry-deployer`, `node-1`, `node-2`, and `node-3`.
- Three Pub/Sub nodes reaching full mesh.
- Ping/pong RTT logging.
- 600-second soak.
- Node-1 stop/start with persisted node identity and recovered full mesh.
- Cardano devnet reset/restart health check.

Evidence files present:

- `implementation-reports/evidence/phase-0.1/acceptance-output.log`
- `implementation-reports/evidence/phase-0.1/cardano-tool-versions.txt`
- `implementation-reports/evidence/phase-0.1/cardano-chain-tip.json`
- `implementation-reports/evidence/phase-0.1/cardano-status.json`
- `implementation-reports/evidence/phase-0.1/funded-address-balances.txt`
- `implementation-reports/evidence/phase-0.1/pubsub-node-1-connections-and-ping.log`
- `implementation-reports/evidence/phase-0.1/pubsub-node-2-connections-and-ping.log`
- `implementation-reports/evidence/phase-0.1/pubsub-node-3-connections-and-ping.log`
- `implementation-reports/evidence/phase-0.1/node-1-stop-detection.log`
- `implementation-reports/evidence/phase-0.1/restart-reconnection.log`
- `implementation-reports/evidence/phase-0.1/reset-restart-chain-tip.json`
- `implementation-reports/evidence/phase-0.1/final-result.txt`

Cardano tool versions captured:

- Official image: `cardano-node 11.0.1`, `cardano-cli 11.0.0.0`, no `cardano-testnet`.
- Devnet runtime image: `cardano-node 11.0.1`, `cardano-cli 11.0.0.0`, `cardano-testnet` reports `cardano-node 11.0.0` / `cardano-api 11.0.0.0` / `cardano-cli 11.0.0.0`.

## Notes

- The Nix-based devnet image is now built locally and reused by Docker cache, but it is still a heavyweight dependency for clean machines.
- The acceptance script intentionally waits before funding because Cardano node local transaction submission has a startup delay in this generated testnet configuration.
- The repository appears fully untracked in Git at the time of this report, so `git diff` does not provide a useful tracked-file summary.
