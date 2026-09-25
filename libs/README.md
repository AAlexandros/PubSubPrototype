# Libraries

`libs` is one Gradle project (`:libs`). Its source and tests live in
`src/main/java` and `src/test/java`; Java packages keep the concepts separate.
In particular, `org.pubsub.prototype.protocol` contains wire messages and
identity without Netty, while `org.pubsub.prototype.transport` contains the
Netty connection code.

The repository still has separate Gradle projects for `apps:pubsub-node`,
`apps:replication-server`, and `tools:telemetry`.

Useful tasks:

```text
:libs:test                       Run all library tests
:libs:runRegistryCli             Run the Cardano topic registry CLI
:libs:runReplicationRegistryCli  Run the replication registry CLI
```

The [sampling](docs/peer-sampling.md), [navigation](docs/navigation.md), and
[dissemination](docs/dissemination.md) notes describe their package behavior.
