# Registry administration CLIs

The two Java entrypoints administer the Cardano registries. They run as Gradle
tasks on the operator's machine; the shell scripts in `scripts/registry/` and
`scripts/replication/` are the usual way to invoke them. The testbed calls
these scripts to deploy registries and register servers before its application
HTTP endpoints are available.

| Registry | Java entrypoint | Gradle task | Shell scripts |
| --- | --- | --- | --- |
| Topic | [TopicRegistryCli](../apps/pubsub-node/src/main/java/org/pubsub/prototype/node/registry/cli/TopicRegistryCli.java) | `:apps:pubsub-node:runTopicRegistryCli` | [`scripts/registry/`](../scripts/registry/) |
| Replication | [ReplicationRegistryCli](../apps/replication-server/src/main/java/org/pubsub/prototype/replication/registry/cli/ReplicationRegistryCli.java) | `:apps:replication-server:runReplicationRegistryCli` | [`scripts/replication/`](../scripts/replication/) |

The classes parse operator arguments and call the registry implementations in
`libs`. Those implementations assemble Cardano transactions and queries; the
[Cardano CLI command reference](cardano-cli.md) lists the underlying commands.
Both entrypoints print runtime errors to standard error and exit with status 1.

The applications' HTTP endpoints handle publication, subscriptions, event
storage, replication, and read-only registry status. They do not deploy or
mutate Cardano registries. The separate [node CLI](node-cli.md) is an HTTP
client for a running Pub/Sub node.

## TopicRegistryCli

The Topic Registry CLI receives arguments in this order:

```text
<runtime-dir> <HOST|DEVNET> <command> [signer] [command-arguments]
```

The runtime directory contains local registry deployment metadata and
transaction files. `HOST` runs an installed `cardano-cli`; `DEVNET` runs it
through the local Docker devnet. The shell helper
[`scripts/registry/common.sh`](../scripts/registry/common.sh) supplies the
runtime directory and backend, defaulting to `DEVNET`. Its command wrappers
provide the signer. `deploy`, `query`, and `utxos` default to
`registry-deployer` when no signer is passed; commands with further positional
arguments require the signer position.

| Command and arguments after `<runtime-dir> <backend>` | Result |
| --- | --- |
| `deploy [signer]` | Derive and write the Topic Registry deployment metadata. |
| `query <signer> [--all]` | Print active topics as JSON; `--all` includes tombstones. |
| `utxos [signer]` | Print the registry script UTxOs as JSON. |
| `topic <signer> <topic-id>` | Print one topic as JSON. |
| `create <signer> <name> <admin-hashes-csv-or-dash> <publisher-ids-csv-or-dash> <replication-factor> <retention-period>` | Create a topic and print its ID. |
| `delete <signer> <topic-id>` | Mark a topic inactive. |
| `add-owner`, `remove-owner`, `add-admin`, `remove-admin`, `add-publisher`, `remove-publisher` followed by `<signer> <topic-id> <value>` | Change the corresponding topic role. |
| `set-replication-factor`, `set-retention-period` followed by `<signer> <topic-id> <value>` | Change the corresponding topic setting. |
| `direct-last-owner-removal <signer> <topic-id> <owner-hash>` | Build an intentionally invalid transaction for the validator rejection check. |

For normal use, run the named scripts such as
[`create-topic.sh`](../scripts/registry/create-topic.sh) and
[`query.sh`](../scripts/registry/query.sh). They set up the runtime path and
validate their own arguments before calling the Java entrypoint.

## ReplicationRegistryCli

The Replication Registry CLI takes the command first. Its shell helper is
[`scripts/replication/common.sh`](../scripts/replication/common.sh).

| Command | Result |
| --- | --- |
| `server-id <verification-key-file>` | Print the server ID derived from the Cardano verification key. |
| `deploy <runtime-dir> <HOST|DEVNET>` | Derive and write the Replication Registry deployment metadata; print its validator address. |
| `query <runtime-dir> <HOST|DEVNET> [--all]` | Print active server registrations as a JSON array; `--all` includes inactive records. |
| `register <replication-server-config> <runtime-dir>` | Read the server ID, endpoint, operator, backend, and commitment from the config; submit the registration and print the resulting JSON. |
| `unregister <replication-server-config> <runtime-dir>` | Read the server ID and operator settings from the config; submit an inactive registration. |

For `register` and `unregister`, the server configuration supplies the Cardano
operator and backend. For `deploy` and `query`, the CLI uses
`registry-deployer` as its signer. The scripts
[`register-server.sh`](../scripts/replication/register-server.sh) and
[`query-servers.sh`](../scripts/replication/query-servers.sh) supply the
runtime path and common defaults.
