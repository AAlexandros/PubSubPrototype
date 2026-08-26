# Phase 0.1.2 — Final Integration and Acceptance

## Goal

Complete Phase 0.1 by validating the Cardano devnet and three-node Pub/Sub runtime end-to-end.

## Cardano devnet

- Complete the `cardano-testnet` runtime setup.
- Prefer prebuilt Cardano binaries/images for the pinned version when available.
- Verify:
  - the local chain starts;
  - blocks are produced;
  - `status.sh` reports an advancing chain tip;
  - `stop.sh`, `reset.sh`, and restart work reliably.

## Fund local identities

Use a funded wallet/UTxO provided by the local testnet to fund:

- `registry-deployer`
- `node-1`
- `node-2`
- `node-3`

Update `fund-identities.sh` so it:

1. locates the funded source;
2. submits funding transactions;
3. waits for confirmation;
4. verifies every target address has a positive balance;
5. fails if funding cannot be confirmed.

## Smoke acceptance

Run the full acceptance flow with a short soak:

```bash
PHASE_0_1_SOAK_SECONDS=30 ./scripts/acceptance/phase-0.1.sh
```

Fix any runtime, Docker, Cardano socket, networking, funding, or restart issues found during this run.

## Full acceptance

Run:

```bash
./scripts/acceptance/phase-0.1.sh
```

The script must verify:

1. Java clean build passes.
2. Java tests pass.
3. Cardano devnet starts.
4. Chain tip advances.
5. All four Cardano identities are funded.
6. Three Pub/Sub nodes start.
7. Every Pub/Sub node connects to the other two.
8. `PING` / `PONG` exchange is continuous.
9. RTT is recorded.
10. The three-node runtime remains stable for 10 minutes.
11. Stopping one Pub/Sub node is detected by the others.
12. Restarting the node restores its peer connections automatically.
13. `PING` / `PONG` resumes after reconnect.
14. The environment can be stopped, reset, and started again from a clean state.

## Evidence

Store the successful acceptance output under:

```text
implementation-reports/evidence/phase-0.1/
```

Include at least:

- Cardano chain-tip output;
- funded-address balance output;
- three-node connection logs;
- representative `PING` / `PONG` and RTT logs;
- node restart/reconnection logs;
- final acceptance result.

## Completion criterion

Phase 0.1 is complete when:

```bash
./scripts/acceptance/phase-0.1.sh
```

passes from a clean repository state without manual intervention.
