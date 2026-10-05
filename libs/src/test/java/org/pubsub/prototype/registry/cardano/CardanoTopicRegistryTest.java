package org.pubsub.prototype.registry.cardano;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.registry.RegistryAuthorizationException;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.cardano.cli.CardanoCliUnavailableException;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.registry.cardano.datum.AikenScriptDataCodec;
import org.pubsub.prototype.registry.cardano.datum.CardanoCliUtxoParser;
import org.pubsub.prototype.registry.cardano.datum.CardanoScriptUtxo;
import org.pubsub.prototype.registry.cardano.mutation.TopicMutation;
import org.pubsub.prototype.registry.cardano.mutation.TopicStateTransitions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoTopicRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void deleteTransitionCreatesInactiveTombstone() {
        TopicState topic = topic();

        TopicState tombstone = TopicStateTransitions.apply(topic, TopicMutation.delete());

        assertFalse(tombstone.active());
        assertEquals(topic.topicId(), tombstone.topicId());
    }

    @Test
    void enforcesOwnerAndAdminAuthorization() {
        TopicState topic = new TopicState(new TopicId("a".repeat(64)), "orders", List.of("node-1"),
                List.of("node-2"), List.of(), 1, 60, true);

        assertDoesNotThrow(() -> TopicStateTransitions.authorize("node-2", topic, TopicMutation.addPublisher("node-3")));
        assertThrows(RegistryAuthorizationException.class,
                () -> TopicStateTransitions.authorize("node-3", topic, TopicMutation.addAdmin("node-3")));
    }

    @Test
    void preventsLastOwnerRemoval() {
        assertThrows(RegistryConflictException.class,
                () -> TopicStateTransitions.apply(topic(), TopicMutation.removeOwner("node-1")));
    }

    @Test
    void parsesCardanoCliScriptUtxosWithTopicTokenAndInlineDatum() {
        String topicId = "b".repeat(64);
        String policyId = "1".repeat(56);
        TopicState topic = new TopicState(
                new TopicId(topicId),
                "orders",
                List.of("2".repeat(56)),
                List.of("3".repeat(56)),
                List.of("4".repeat(64)),
                3,
                7200,
                true
        );
        String datum = new AikenScriptDataCodec().encodeDatum(topic, value -> value);
        String json = """
                {
                  "abc#0": {
                    "address": "addr_test_registry",
                    "value": {
                      "lovelace": 2000000,
                      "%s": {
                        "%s": 1
                      }
                    },
                    "inlineDatum": %s
                  }
                }
                """.formatted(policyId, topicId, datum);

        List<CardanoScriptUtxo> utxos = new CardanoCliUtxoParser().parse(json);

        assertEquals(1, utxos.size());
        assertTrue(utxos.getFirst().containsPolicy(policyId));
        assertEquals(new TopicId(topicId), utxos.getFirst().topicState().orElseThrow().topicId());
        assertEquals(topic.publishers(), utxos.getFirst().topicState().orElseThrow().publishers());
    }

    @Test
    void cacheOnlyBackendDoesNotAllowTransactionSubmission() {
        CardanoCommandRunner runner = CardanoCommandRunners.create(new CardanoRegistryConfig(
                Path.of("/registry"), "node-1", CardanoCliBackend.CACHE_ONLY));

        assertTrue(runner.queryScriptUtxosJson("address").isEmpty());
        assertThrows(CardanoCliUnavailableException.class, runner::requireCommandExecution);
    }

    @Test
    void hostBackendRequiresCompleteNetworkConfiguration() throws IOException {
        Path runtimeDir = tempDir.resolve("runtime/registry");
        Files.createDirectories(runtimeDir);
        Files.writeString(runtimeDir.getParent().resolve("network.env"), "CARDANO_NETWORK_MAGIC=42\n");

        CardanoRegistryConfig config = new CardanoRegistryConfig(
                runtimeDir, "node-1", CardanoCliBackend.HOST);

        assertThrows(IllegalArgumentException.class, () -> CardanoCommandRunners.create(config));
    }

    @Test
    void devnetBackendIsConfiguredWithoutAHostSocket() throws IOException {
        Path devnetDir = tempDir.resolve("devnet");
        Path runtimeDir = devnetDir.resolve("runtime/registry");
        Files.createDirectories(runtimeDir);
        Files.writeString(devnetDir.resolve("compose.yaml"), "services: {}\n");
        Files.writeString(devnetDir.resolve("versions.env"), "CARDANO_NETWORK_MAGIC=42\n");
        Files.writeString(runtimeDir.getParent().resolve("network.env"), "CARDANO_NETWORK_MAGIC=42\n");

        CardanoCommandRunner runner = CardanoCommandRunners.create(new CardanoRegistryConfig(
                runtimeDir, "node-1", CardanoCliBackend.DEVNET));

        assertEquals("42", runner.networkMagic());
    }

    private static TopicState topic() {
        return new TopicState(new TopicId("a".repeat(64)), "orders", List.of("node-1"),
                List.of(), List.of(), 1, 60, true);
    }
}
