# Client and operator commands

Run shell commands from the repository root with Bash. The scripts here serve
real Pub/Sub nodes and Cardano registry operators.

| Path | Purpose |
| --- | --- |
| [`pubsub-node.mjs`](pubsub-node.mjs) | Send commands to a running node's HTTP adapter: publish, subscribe, inspect events, and check status. |
| [`registry/`](registry/) | Build, deploy, query, and administer the Cardano Topic Registry. |
| [`replication/`](replication/) | Build, deploy, query, and administer the Cardano Replication Registry. |
| [`lib/`](lib/) | Shared path and platform helpers used by the commands above. |

Registry scripts default to the host `cardano-cli` backend and store their
transaction files and deployment metadata under `runtime/cardano/`. Configure
`CARDANO_NETWORK_MAGIC` and `CARDANO_NODE_SOCKET_PATH`, or put those values in
`runtime/cardano/network.env`. Signer payment keys belong under
`runtime/cardano/keys/<signer>/`. Override the registry working directories with
`REGISTRY_RUNTIME_DIR` and `REPLICATION_REGISTRY_RUNTIME_DIR` if needed.

See the [deployment guide](../documentation/deployment.md) for the operator
workflow and the [script reference](../documentation/scripts.md) for commands.
Local devnet, testbed, and acceptance helpers live under [`test/`](../test/README.md).
