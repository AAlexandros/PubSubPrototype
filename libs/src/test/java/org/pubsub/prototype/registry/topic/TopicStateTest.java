package org.pubsub.prototype.registry.topic;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicMutation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicStateTest {
    @Test
    void topicStateRequiresValidAdministrativeInvariants() {
        TopicId topicId = new TopicId("1".repeat(64));

        assertThrows(IllegalArgumentException.class, () ->
                new TopicState(topicId, "orders", List.of(), List.of(), List.of(), 1, 60, true));
        assertThrows(IllegalArgumentException.class, () ->
                new TopicState(topicId, "orders", List.of("node-1"), List.of(), List.of(), 0, 60, true));
        assertThrows(IllegalArgumentException.class, () ->
                new TopicState(topicId, "orders", List.of("node-1"), List.of(), List.of(), 1, 0, true));
    }

    @Test
    void replicationFactorIsLimitedToFiftyAtCreationAndUpdate() {
        TopicId topicId = new TopicId("1".repeat(64));
        TopicState topic = new TopicState(topicId, "orders", List.of("node-1"),
                List.of(), List.of(), 50, 60, true);
        assertEquals(50, topic.replicationFactor());
        assertThrows(IllegalArgumentException.class, () -> topic.withReplicationFactor(51));
        assertThrows(IllegalArgumentException.class, () ->
                new CreateTopicRequest("orders", List.of(), List.of(), 51, 60));
        assertThrows(IllegalArgumentException.class, () -> TopicMutation.setReplicationFactor(51));
    }
}
