package org.pubsub.prototype.registry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistrySnapshotTest {
    @Test
    void snapshotsCompareByTopicContentAndObservationTime() {
        TopicState topic = new TopicState(
                new TopicId("0".repeat(64)),
                "orders",
                List.of("node-1"),
                List.of(),
                List.of(),
                1,
                60,
                true
        );
        Instant observedAt = Instant.parse("2026-08-27T00:00:00Z");

        assertEquals(new RegistrySnapshot(List.of(topic), observedAt), new RegistrySnapshot(List.of(topic), observedAt));
    }

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
}
