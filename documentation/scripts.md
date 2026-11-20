# Client and operator scripts

Run shell commands from the repository root with Bash (Git Bash or WSL on
Windows). [Deployment](deployment.md) describes the required Cardano socket,
network magic, keys, and application configuration.

## Running node commands

[`scripts/pubsub-node.mjs`](../scripts/pubsub-node.mjs) calls the running node's
HTTP adapter to publish, subscribe, inspect events, and check status. See the
[node CLI guide](node-cli.md) for commands and options.

## Cardano registry commands

The scripts in [`scripts/registry/`](../scripts/registry/) build and deploy the
Topic Registry, create and delete topics, change owners, administrators,
publishers, replication factor and retention, and query topics. Typical commands:

```bash
scripts/registry/build.sh
scripts/registry/deploy.sh
scripts/registry/create-topic.sh <signer> <name> <admin-hashes-csv-or-dash> <publisher-ids-csv-or-dash> <replication-factor> <retention-period>
scripts/registry/query.sh
```

The scripts in [`scripts/replication/`](../scripts/replication/) build and
deploy the Replication Registry, register or unregister servers, and query
registrations:

```bash
scripts/replication/build.sh
scripts/replication/deploy.sh
scripts/replication/register-server.sh <replication-server-config.yaml>
scripts/replication/query-servers.sh
```

The shell wrappers call the registry Java CLIs through Gradle. The [registry
CLI reference](registry-clis.md) lists every command and argument; the
[Cardano CLI reference](cardano-cli.md) explains the transactions they submit.
These Cardano mutations need an operator signer and are separate from the
node's runtime HTTP API.

## Local test infrastructure

Devnet, testbed, fixtures, and acceptance commands live in the
[test guide](../test/README.md).
