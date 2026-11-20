# Running a client on a Cardano network

This guide describes the operator path for an existing Cardano node. The local
Docker devnet and sample topology are documented in [test/](../test/README.md).

## Build

Install JDK 21, Node.js 18 or newer, Bash, and `cardano-cli`. Contract builds
also need Aiken or a supported platform fallback. Build from the repository root:

```bash
./gradlew :apps:pubsub-node:installDist :apps:replication-server:installDist
scripts/registry/build.sh
scripts/replication/build.sh
```

The Gradle wrapper is included. Contract build scripts use local Aiken when
available and otherwise select a platform fallback.

## Connect the operator commands to Cardano

Give the operator machine access to the Cardano node socket and set the network
magic. Use a Cardano payment identity with enough funds for registry
transactions. The default signer directory is
`runtime/cardano/keys/<signer>/` and contains `payment.skey`, `payment.vkey`,
and `payment.addr` for each signer. Protect the signing keys; the node and
replication server runtime do not need the registry operator's signing key.

```bash
export CARDANO_NETWORK_MAGIC=<network-magic>
export CARDANO_NODE_SOCKET_PATH=<absolute-path-to-node-socket>
```

Alternatively, put those two assignments in `runtime/cardano/network.env`.
The registry scripts default to `CARDANO_CLI_BACKEND=HOST`, with metadata and
transaction files under `runtime/cardano/registry/` and
`runtime/cardano/replication-registry/`. Set `REGISTRY_RUNTIME_DIR` or
`REPLICATION_REGISTRY_RUNTIME_DIR` to use other locations. Keep the runtime
directories and signer keys together under the same parent layout:
`<base>/<registry-name>/` and `<base>/keys/<signer>/`.

## Deploy and administer the registries

```bash
scripts/registry/deploy.sh
scripts/replication/deploy.sh
scripts/registry/create-topic.sh <signer> <name> <admin-hashes-csv-or-dash> <publisher-ids-csv-or-dash> <replication-factor> <retention-period>
scripts/registry/query.sh
scripts/replication/query-servers.sh
```

The topic deployment writes `runtime/cardano/registry/deployment.env`; the
replication deployment writes
`runtime/cardano/replication-registry/deployment.env`. Keep these files for
application registry reads. To register a server, provide its YAML config with
`registration.operator` set to a funded signer and
`registration.cliBackend: HOST`, then run:

```bash
scripts/replication/server-id.sh <payment.vkey>
scripts/replication/register-server.sh <server-config.yaml>
```

The server ID in the config must match the one derived from that verification
key. The [registry CLI reference](registry-clis.md) lists all administration
commands and their arguments.

## Run application processes

Create a node or server YAML configuration with its own identity or storage
path, reachable advertised address, registry metadata paths, and HTTP ports.
For example, a node configuration is:

```yaml
node: {name: node-1, listenHost: 0.0.0.0, listenPort: 7000, identityPath: /var/lib/pubsub/node-1}
peers: [{host: peer.example.net, port: 7000}]
transport: {pingIntervalMs: 3000, pingTimeoutMs: 2000, reconnectInitialMs: 500, reconnectMaxMs: 5000}
registry: {runtimeDir: /srv/pubsub/cardano/registry, replicationRuntimeDir: /srv/pubsub/cardano/replication-registry, signer: node-1, cliBackend: HOST, pollIntervalMs: 5000, maximumAgeMs: 30000}
control: {host: 127.0.0.1, port: 8000}
peerSampling: {advertisedHost: node-1.example.net, viewSize: 8, swapLength: 4, cycleIntervalMs: 1000, ageThreshold: 20, proofFanout: 8, randomSeed: 9001}
navigation: {capacity: 4, routingBase: 2, cycleIntervalMs: 1000, staleAfterMs: 15000, subscriptionsPath: /var/lib/pubsub/node-1/subscriptions.json}
dissemination: {randomLinkCount: 2, cycleIntervalMs: 1000, staleAfterMs: 15000, randomSeed: 19001}
persistence: {deliveryStatePath: /var/lib/pubsub/node-1/recovery-state.json, connectionTimeoutMs: 1000, requestTimeoutMs: 3000, retries: 1, recoveryConcurrency: 8}
```

A replication server configuration is:

```yaml
server:
  serverId: <64-character-id-from-server-id.sh>
  listenHost: 0.0.0.0
  advertisedHost: replica-1.example.net
  port: 8100
  storagePath: /var/lib/pubsub/replica-1
  requestSigningKeyPath: /srv/pubsub/server-1/payment.skey
  requestVerificationKeyPath: /srv/pubsub/server-1/payment.vkey
registration: {operator: registry-deployer, cliBackend: HOST, commitmentStartEpoch: 0, commitmentEndEpoch: 100000}
registry: {replicationRuntimeDir: /srv/pubsub/cardano/replication-registry, pollIntervalMs: 5000, maximumAgeMs: 30000, topicRegistryRuntimeDir: /srv/pubsub/cardano/registry, topicRegistrySigner: registry-deployer, topicRegistryCliBackend: HOST}
timing: {connectionTimeoutMs: 500, requestTimeoutMs: 1500, retries: 0, cleanupIntervalMs: 1000, failureProbeAttempts: 3, failureProbeTimeoutMs: 500, maintenanceIntervalMs: 500}
```

Replace the example addresses and paths with your deployment values. Give the
applications read access to both `deployment.env` files and the Cardano node
socket. Set `CARDANO_NETWORK_MAGIC` and `CARDANO_NODE_SOCKET_PATH` in each
process environment. The runtime does not need the registry operator's key.

```bash
apps/pubsub-node/build/install/pubsub-node/bin/pubsub-node <node-config.yaml>
apps/replication-server/build/install/replication-server/bin/replication-server <server-config.yaml>
```

Use [`scripts/pubsub-node.mjs`](../scripts/pubsub-node.mjs) to send publish,
subscribe, event-history, and status commands to a running node. Its HTTP
address is the node configuration's `control` host and port. The
[node CLI guide](node-cli.md) describes the address options.
