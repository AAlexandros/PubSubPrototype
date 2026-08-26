# Phase 0.1 — Repository, Cardano Devnet, and Three-Node Transport

**Goal:** create the project baseline: a reproducible Java monorepo, a local Cardano devnet, and three Pub/Sub nodes that maintain stable identities and exchange `PING`/`PONG` messages.

## Technical baseline

| Area | Choice |
|---|---|
| Java | 21 |
| Build | Gradle Wrapper, Kotlin DSL |
| Networking | Netty TCP |
| Serialization | Jackson JSON |
| Framing | 4-byte big-endian length prefix + JSON |
| Identity | Persistent Ed25519 key pair |
| Node ID | `SHA-256(publicKey)` as lowercase hex |
| Logging | SLF4J + Logback |
| Tests | JUnit 5 |
| Runtime | Docker Compose |
| Cardano | Local single-block-producer devnet |
| Network magic | `42` |


### Modules

- **`protocol-core`** — node IDs, protocol messages, validation, protocol version. No Netty/Cardano dependencies.
- **`transport-netty`** — TCP server/client, framing, handshake, ping/pong, reconnect.
- **`pubsub-node`** — configuration, identity loading, module wiring, application lifecycle, logging.

---

# M0 — Repository and Cardano devnet baseline

## M0.1 Repository bootstrap

Create the Gradle multi-module project.

Required commands:

```bash
./gradlew clean build
./gradlew test
```

Add CI that runs both commands. Gitignore generated keys, logs, Cardano state, and runtime data.

## M0.2 Cardano devnet

Create a Docker-based devnet containing one block-producing Cardano node.

Provide:

```text
infra/devnet/compose.yaml
infra/devnet/scripts/init.sh
infra/devnet/scripts/start.sh
infra/devnet/scripts/stop.sh
infra/devnet/scripts/status.sh
infra/devnet/scripts/reset.sh
infra/devnet/scripts/wait-healthy.sh
infra/devnet/scripts/fund-identities.sh
```

`init.sh` must create the devnet configuration/genesis and four funded payment identities:

```text
deployer
node-1
node-2
node-3
```

Generated keys and state stay under `infra/devnet/` and are gitignored.

Export:

```text
infra/devnet/runtime/network.env
```

with at least:

```bash
CARDANO_NETWORK_MAGIC=42
CARDANO_NODE_SOCKET_PATH=...
```

`status.sh` must query the chain tip. `wait-healthy.sh` succeeds only after blocks are being produced.

### M0 acceptance

- Gradle build/tests pass.
- Devnet initializes from a clean state.
- Cardano node starts and produces blocks.
- Chain tip advances.
- Four funded identities exist.
- Devnet can be stopped, restarted, and reset through scripts.
- README is enough for a fresh developer to boot and verify the stack

---

# M1 — Three-node transport and ping

## M1.1 Node configuration

Each node has a YAML configuration:

```yaml
node:
  name: node-1
  listenHost: 0.0.0.0
  listenPort: 7001
  identityPath: /data/identity

peers:
  - host: node-2
    port: 7002
  - host: node-3
    port: 7003

transport:
  pingIntervalMs: 5000
  pingTimeoutMs: 3000
  reconnectInitialMs: 1000
  reconnectMaxMs: 10000
```

Provide equivalent files for nodes 2 and 3.

## M1.2 Node identity

On first start, generate and persist an Ed25519 key pair. Reuse it on restart.

Derive:

```text
NodeId = SHA-256(publicKey)
```

Log the node name, `NodeId`, and listen address on startup.

## M1.3 Wire protocol

Protocol version: `1`.

Required messages:

| Message | Required fields |
|---|---|
| `HELLO` | `version`, `nodeId`, `nodeName` |
| `HELLO_ACK` | `version`, `nodeId` |
| `PING` | `version`, `requestId`, `sentAt` |
| `PONG` | `version`, `requestId`, `sentAt` |

A connection is active only after a successful HELLO handshake.

Reject malformed frames, unsupported protocol versions, self-connections, and invalid node IDs without crashing the process.

## M1.4 Ping/Pong

For every active peer:

1. Send `PING` every `pingIntervalMs`.
2. Reply immediately with `PONG` using the same `requestId`.
3. Record round-trip time.
4. Mark a ping failed after `pingTimeoutMs`.

Required log events:

```text
NODE_STARTED
PEER_CONNECTED
PEER_DISCONNECTED
PING_SENT
PONG_RECEIVED
PING_TIMEOUT
RECONNECT_SCHEDULED
```

## M1.5 Reconnect

When a connection is lost, reconnect automatically using bounded exponential backoff between `reconnectInitialMs` and `reconnectMaxMs`. Run the HELLO handshake again after reconnect.

## M1.6 Docker runtime

`infra/phase-0.1/compose.yaml` must start:

```text
cardano-node
pubsub-node-1
pubsub-node-2
pubsub-node-3
```

Each Pub/Sub node gets its own persistent identity volume.

Expected topology:

```text
node-1 <-> node-2
node-1 <-> node-3
node-2 <-> node-3
```

Provide:

```bash
./scripts/phase-0.1-up.sh
./scripts/phase-0.1-down.sh
```

## M1.7 Tests

Unit tests:

- Node ID derivation.
- Message encode/decode.
- Handshake validation.
- Ping/Pong correlation.
- Reconnect backoff.

Integration tests:

- Three nodes establish full-mesh connectivity.
- Every pair exchanges PING/PONG.
- Node ID remains stable after restart.
- Restarted node reconnects automatically.
- Malformed input does not crash a node.

---


