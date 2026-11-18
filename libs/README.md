# Libraries

`libs` is one Gradle project (`:libs`). Its source and tests live in
`src/main/java` and `src/test/java`; Java packages keep the concepts separate.
In particular, `org.pubsub.prototype.protocol` contains wire messages and
identity without Netty, while `org.pubsub.prototype.transport` contains the
Netty connection code.

The repository has separate Gradle projects for `apps:pubsub-node` and
`apps:replication-server`. Each app also provides a registry administration CLI.

Useful tasks:

```text
:libs:test                       Run all library tests
:apps:pubsub-node:runTopicRegistryCli
                                 Run the Cardano topic registry CLI
:apps:replication-server:runReplicationRegistryCli
                                 Run the Cardano replication registry CLI
```

Both registries use the shared Cardano CLI runner and transaction support in
`org.pubsub.prototype.cardano.cli`; their deployment, datum, mutation, and
transaction-building code remains contract-specific.

The Topic Registry implementation is under
`org.pubsub.prototype.registry.topic.cardano`; the separate Replication
Registry is under `org.pubsub.prototype.registry.replication.cardano`.
Persistence exposes the replication membership client and reader contracts,
not the Cardano adapter.
