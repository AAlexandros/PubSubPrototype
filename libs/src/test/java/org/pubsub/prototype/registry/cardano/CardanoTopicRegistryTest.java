package org.pubsub.prototype.registry.cardano;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.registry.RegistryAuthorizationException;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoTopicRegistryTest {
    @Test
    void datumRoundTripsThroughJson() {
        TopicState topic = new TopicState(new TopicId("a".repeat(64)), "orders", List.of("node-1"),
                List.of("node-2"), List.of("node-3"), 3, 600, true);
        TopicDatumCodec codec = new TopicDatumCodec();

        assertEquals(topic, codec.decode(codec.encode(topic)));
    }

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
                    "inlineDatum": {
                      "topicId": { "value": "%s" },
                      "name": "orders",
                      "owners": [ "node-1" ],
                      "admins": [ "node-2" ],
                      "publishers": [ "node-3" ],
                      "replicationFactor": 3,
                      "retentionPeriod": 7200,
                      "active": true
                    }
                  }
                }
                """.formatted(policyId, topicId, topicId);

        List<CardanoScriptUtxo> utxos = new CardanoCliUtxoParser().parse(json);

        assertEquals(1, utxos.size());
        assertTrue(utxos.getFirst().containsPolicy(policyId));
        assertEquals(new TopicId(topicId), utxos.getFirst().topicState().orElseThrow().topicId());
        assertEquals(List.of("node-3"), utxos.getFirst().topicState().orElseThrow().publishers());
    }

    @Test
    void containerRegistryPathWithoutDevnetDoesNotThrowDuringAvailabilityProbe() {
        CardanoCli cli = new CardanoCli(new CardanoRegistryConfig(java.nio.file.Path.of("/registry"), "node-1"));

        assertDoesNotThrow(cli::available);
    }

    private static TopicState topic() {
        return new TopicState(new TopicId("a".repeat(64)), "orders", List.of("node-1"),
                List.of(), List.of(), 1, 60, true);
    }
}
