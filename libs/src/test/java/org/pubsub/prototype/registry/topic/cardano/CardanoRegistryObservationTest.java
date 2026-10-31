package org.pubsub.prototype.registry.topic.cardano;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.cardano.CardanoEnvironmentFile;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.cardano.cli.CardanoCommand;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.registry.RegistryDataException;
import org.pubsub.prototype.registry.RegistryIdentityException;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistry;
import org.pubsub.prototype.registry.replication.cardano.CardanoReplicationRegistryConfig;
import org.pubsub.prototype.registry.replication.cardano.ReplicationRegistryEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardanoRegistryObservationTest {
    @TempDir Path temp;

    @Test
    void topicReadRequiresACompleteLiveQueryAndRejectsUnexpectedIdentity() throws Exception {
        Path dir = temp.resolve("topic");
        CardanoRegistryConfig config = new CardanoRegistryConfig(dir, "operator", CardanoCliBackend.HOST);
        CardanoEnvironmentFile.write(config.deploymentFile(), Map.of(
                CardanoRegistryEnvironment.TOPIC_REGISTRY_VALIDATOR_ADDRESS, "addr_test1topic",
                CardanoRegistryEnvironment.TOPIC_POLICY_ID, "ab".repeat(28)));
        Files.writeString(dir.resolve("script-utxos.json"), "{}");
        FakeRunner runner = new FakeRunner();
        CardanoTopicRegistry registry = new CardanoTopicRegistry(config, runner);

        assertThrows(RegistryConflictException.class, registry::allTopicsSnapshot);
        runner.response = Optional.of("{}");
        assertEquals(0, registry.allTopicsSnapshot().size());
        runner.response = Optional.of("[]");
        assertThrows(RegistryDataException.class, registry::allTopicsSnapshot);
        runner.response = Optional.of("{\"tx#0\":{\"address\":\"addr_test1topic\",\"value\":{\"lovelace\":2000000}}}");
        assertEquals(0, registry.allTopicsSnapshot().size());
        runner.response = Optional.of("{\"tx#0\":{\"address\":\"addr_test1topic\",\"value\":{\"lovelace\":2000000,\""
                + "ab".repeat(28) + "\":{\"deadbeef\":1}}}}");
        assertThrows(RegistryDataException.class, registry::allTopicsSnapshot);
        runner.response = Optional.of("{\"tx#0\":{\"address\":\"wrong\",\"value\":{\"lovelace\":2000000}}}");
        assertThrows(RegistryIdentityException.class, registry::allTopicsSnapshot);
    }

    @Test
    void replicationReadRequiresACompleteLiveQuery() throws Exception {
        Path dir = temp.resolve("replication");
        CardanoReplicationRegistryConfig config = new CardanoReplicationRegistryConfig(
                dir, "operator", CardanoCliBackend.HOST);
        CardanoEnvironmentFile.write(config.deploymentFile(), Map.of(
                ReplicationRegistryEnvironment.VALIDATOR_ADDRESS, "addr_test1replication"));
        Files.writeString(dir.resolve("servers.json"), "[]");
        FakeRunner runner = new FakeRunner();
        CardanoReplicationRegistry registry = new CardanoReplicationRegistry(config, runner);

        assertThrows(IllegalStateException.class, () -> registry.queryServers(true));
        runner.response = Optional.of("{}");
        assertEquals(0, registry.queryServers(true).size());
        runner.response = Optional.of("[]");
        assertThrows(RegistryDataException.class, () -> registry.queryServers(true));
        runner.response = Optional.of("{\"tx#0\":{\"address\":\"addr_test1replication\",\"value\":{\"lovelace\":2000000}}}");
        assertThrows(RegistryDataException.class, () -> registry.queryServers(true));
        runner.response = Optional.of("{\"tx#0\":{\"address\":\"wrong\",\"value\":{\"lovelace\":2000000}}}");
        assertThrows(RegistryIdentityException.class, () -> registry.queryServers(true));
    }

    private static final class FakeRunner implements CardanoCommandRunner {
        private Optional<String> response = Optional.empty();
        @Override public String networkMagic() { return "42"; }
        @Override public String run(CardanoCommand command) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> queryScriptUtxosJson(String address) { return response; }
        @Override public void requireCommandExecution() { }
    }
}
