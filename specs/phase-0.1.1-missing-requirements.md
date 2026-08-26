# Phase 0.1 — Missing Requirements

This document lists the remaining work required to complete Phase 0.1 based on the SRS and the Phase 0.1 implementation report.

## Devnet decision

Use Cardano's existing local-cluster tooling rather than implementing a devnet from scratch.

The preferred tool is `cardano-testnet`, which is part of the `cardano-node` project. It can generate the local network configuration and genesis files and start a local Cardano cluster.

The repository only needs to **integrate and automate** this tool.

### Required setup

- Pin compatible versions of:
  - `cardano-node`
  - `cardano-cli`
  - `cardano-testnet`
- Make the selected versions reproducible through the Docker/devnet setup.
- Configure `cardano-testnet` for a minimal local cluster suitable for contract development.

## M0 — Cardano devnet baseline

The current placeholder devnet scripts must be replaced with working automation.

### Required

- `infra/devnet` starts a real local Cardano network using `cardano-testnet`.
- The network produces blocks.
- `status.sh` verifies that the chain tip is advancing.
- `wait-healthy.sh` waits until the network is usable.
- `stop.sh` stops the network cleanly.
- `reset.sh` removes runtime state and allows a clean restart.

### Test identities

Provide four local Cardano payment identities:

- `registry-deployer`
- `node-1`
- `node-2`
- `node-3`

Each identity must:

- have a payment signing key;
- have a payment verification key;
- have a testnet address;
- contain enough test ADA for later prototype transactions.

The acceptance test must query and verify their balances.

### Runtime configuration

Generate a runtime environment file containing at least:

```text
CARDANO_NODE_SOCKET_PATH
CARDANO_NETWORK_MAGIC
REGISTRY_DEPLOYER_ADDRESS
NODE_1_CARDANO_ADDRESS
NODE_2_CARDANO_ADDRESS
NODE_3_CARDANO_ADDRESS
```

Later Java/Cardano integration must consume these values rather than hard-code network parameters.

## M1 — Three-node runtime validation

The Java implementation is present, but the complete runtime acceptance scenario still needs to be executed.

### Required

- Start the devnet.
- Wait for Cardano health.
- Start `node-1`, `node-2`, and `node-3`.
- Verify that every Pub/Sub node connects to the other two.
- Verify continuous `PING` / `PONG` exchange.
- Verify RTT values are logged.
- Run the three-node network for at least 10 minutes without unhandled exceptions or connection leaks.
- Stop one Pub/Sub node.
- Verify the other nodes detect the disconnection.
- Restart the stopped node.
- Verify automatic reconnection and resumed `PING` / `PONG`.

## Phase 0.1 acceptance

The following command must run from a clean state and exit successfully:

```bash
./scripts/acceptance/phase-0.1.sh
```

It must verify:

1. Java clean build and tests pass.
2. Cardano devnet starts successfully.
3. Cardano chain tip advances.
4. The four Cardano identities exist and are funded.
5. Three Pub/Sub nodes start successfully.
6. All three nodes establish the expected peer connections.
7. `PING` / `PONG` and RTT logging work.
8. The 10-minute soak test succeeds.
9. Restarting one Pub/Sub node triggers disconnect detection and successful reconnection.
10. The environment can be stopped, reset, and started again without manual file changes.

Phase 0.1 is complete when this acceptance script passes from a clean repository state.

## References

- Phase 0.1 implementation report.
- Pub/Sub Prototype SRS v0.1.
- Cardano Developer Portal — Testnets and Devnets:
  https://developers.cardano.org/docs/get-started/testnets-and-devnets/
- Cardano Docs — Local Testnet:
  https://docs.cardano.org/cardano-testnets/local-testnet
