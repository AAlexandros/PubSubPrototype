# Cardano Pub/Sub Prototype

A Java 21 publish/subscribe client with Cardano registries for topic policy and
replication server membership. Pub/Sub nodes deliver events over a Netty peer
network. Replication servers store events for recovery; event payloads are not
written to Cardano.

## Client components

| Path | Purpose |
| --- | --- |
| [`apps/pubsub-node`](apps/pubsub-node) | Runnable node, local HTTP command adapter, peer overlays, delivery, and recovery. |
| [`apps/replication-server`](apps/replication-server) | Runnable event storage server, placement, repair, and recovery HTTP API. |
| [`libs`](libs) | Shared protocol, Cardano, registry, transport, and persistence code. |
| [`contracts`](contracts) | Aiken Topic and Replication Registry contracts. |
| [`scripts`](scripts/README.md) | Node HTTP client and Cardano registry operator commands. |
| [`documentation`](documentation/deployment.md) | Deployment, command, and protocol guides. |
| [`test`](test/README.md) | Unit tests, local Cardano devnet, integrated testbed, fixtures, and acceptance scenarios. |

## Build the client

Install JDK 21. Use the included Gradle wrapper from the repository root:

```bash
./gradlew :apps:pubsub-node:installDist :apps:replication-server:installDist
```

On Windows PowerShell, use `./gradlew.bat` with the same tasks. Dependencies
are declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
The installed applications are under each app's `build/install/` directory.

## Operate on a Cardano network

The [deployment guide](documentation/deployment.md) covers Cardano node access,
signer keys, contract deployment, topic creation, server registration, and
application configuration. Registry administration uses signed Cardano
transactions through [`scripts/registry/`](scripts/registry/) and
[`scripts/replication/`](scripts/replication/). The scripts use the host
`cardano-cli` by default. They are part of the operator workflow; the node's
HTTP API is for publishing, subscriptions, and runtime inspection.

The local node command client is [`scripts/pubsub-node.mjs`](scripts/pubsub-node.mjs):

```bash
node scripts/pubsub-node.mjs status --url http://127.0.0.1:8000
node scripts/pubsub-node.mjs subscribe --url http://127.0.0.1:8000 --topic <topic-id>
node scripts/pubsub-node.mjs publish --url http://127.0.0.1:8000 --topic <topic-id> --payload "hello"
```

See the [node command reference](documentation/node-cli.md) for all commands
and address options.

## Local development and tests

The [test guide](test/README.md) explains the Docker devnet, integrated
testbed, Java and Node tests, and acceptance scenarios. The client applications,
contracts, libraries, and operator commands build without `test/`.
