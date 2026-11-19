# Scripts reference

This guide lists the repository's current command-line entrypoints. Run shell
scripts from the repository root in Bash (Git Bash or WSL on Windows), unless a
command is explicitly shown for PowerShell. For Cardano command behavior, see
the [Cardano CLI reference](cardano-cli.md). For commands sent to a running
Pub/Sub node, see the [node CLI guide](node-cli.md).

## Build

| Command | Purpose |
| --- | --- |
| `./gradlew --gradle-user-home .gradle-user-home test` | Run the Gradle test suite from Bash. |
| `./gradlew.bat -g .gradle-user-home test` | Run the Gradle test suite from Windows PowerShell or Command Prompt. |

## Integrated testbed

The testbed entrypoints manage the local Cardano devnet, registries, Pub/Sub
nodes, and replication servers. The default topology has three nodes; `up.sh`
accepts `--nodes N` for topologies from 3 to 50 nodes.

| Command | Purpose |
| --- | --- |
| [`scripts/testbed/up.sh`](../scripts/testbed/up.sh) | Prepare the testbed when needed and start its services. Example: `./scripts/testbed/up.sh --nodes 3`. |
| [`scripts/testbed/status.sh`](../scripts/testbed/status.sh) | Show service and API health. |
| [`scripts/testbed/logs.sh`](../scripts/testbed/logs.sh) | Follow testbed logs. |
| [`scripts/testbed/collect-logs.sh`](../scripts/testbed/collect-logs.sh) | Copy diagnostic logs to an output directory. |
| [`scripts/testbed/down.sh`](../scripts/testbed/down.sh) | Stop services while retaining persistent volumes and local state. |
| [`scripts/testbed/stop.sh`](../scripts/testbed/stop.sh) | Stop testbed containers while retaining their data. |
| [`scripts/testbed/restart.sh`](../scripts/testbed/restart.sh) | Restart the running testbed. |
| [`scripts/testbed/reset.sh`](../scripts/testbed/reset.sh) | Remove generated testbed/devnet state and Docker volumes, then reinitialize by default. This is destructive to local testbed data. |
| [`scripts/testbed/generate.mjs`](../scripts/testbed/generate.mjs) | Generate per-node configuration and Docker Compose files. |

## Cardano devnet

The scripts under [`ops/infra/devnet/scripts/`](../ops/infra/devnet/scripts/)
initialize, start, inspect, and stop the local Cardano network. The common
helper selects a host-installed Cardano CLI when available or the pinned
Docker-based tools otherwise.

| Command | Purpose |
| --- | --- |
| `./ops/infra/devnet/scripts/init.sh` | Create local devnet identities and initial configuration. |
| `./ops/infra/devnet/scripts/start.sh` | Start the local Cardano devnet. |
| `./ops/infra/devnet/scripts/wait-healthy.sh` | Wait for the Cardano chain tip to advance. |
| `./ops/infra/devnet/scripts/fund-identities.sh` | Fund the configured local identities. |
| `./ops/infra/devnet/scripts/status.sh` | Read and print the chain tip. |
| `./ops/infra/devnet/scripts/stop.sh` | Stop the local devnet. |
| `./ops/infra/devnet/scripts/reset.sh` | Reset generated devnet state and initialize it again. |

## Registry operations

The registry scripts live in [`scripts/registry/`](../scripts/registry/) and
[`scripts/replication/`](../scripts/replication/). They build and deploy the
Aiken contracts, manage topic or server registrations, and query on-chain
state. Both registries use the same layout: the Topic Registry CLI is in
`apps/pubsub-node`, the Replication Registry CLI is in
`apps/replication-server`, and the shell scripts call their Gradle tasks.
The [registry CLI reference](registry-clis.md) documents both Java entrypoints
and their arguments.
The [Cardano CLI reference](cardano-cli.md) describes the transaction
commands assembled by the Java and shell tools. See the
[registry observation guide](registry-observation.md) for polling and freshness
behavior in running nodes and servers.

Common Topic Registry commands include `build.sh`, `deploy.sh`,
`create-topic.sh`, `delete-topic.sh`, `add-owner.sh`, `remove-owner.sh`,
`add-admin.sh`, `remove-admin.sh`, `add-publisher.sh`,
`remove-publisher.sh`, `set-replication-factor.sh`,
`set-retention-period.sh`, and `query.sh`.

Replication Registry commands include `build.sh`, `deploy.sh`,
`register-server.sh`, `unregister-server.sh`, and `query-servers.sh`.

## Node commands and acceptance

Use [`scripts/pubsub-node.mjs`](../scripts/pubsub-node.mjs) for local publish,
subscribe, event-history, recovery, status, and overlay commands. Compatibility
wrappers for event publishing and injection are in
[`scripts/events/`](../scripts/events/).

Automated tests are documented in the [system test guide](../test/README.md).
Operational scripts and the original three-node lifecycle helpers remain under
[`scripts/`](../scripts/) and [`ops/scripts/`](../ops/scripts/).

## Experiments

Experiment workloads, datasets, aggregation, plots, and result files are
maintained in the separate
[PubSubPrototype-Experiments repository](https://github.com/AAlexandros/PubSubPrototype-Experiments).
Its scripts can use the local node command ports exposed by this testbed.
