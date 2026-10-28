package org.pubsub.prototype.registry.topic;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores the state of the topic registry for a given point in time.
 */
public record RegistrySnapshot(List<TopicState> topics, Instant observedAt) {
    public RegistrySnapshot {
        topics = List.copyOf(Objects.requireNonNull(topics, RegistryField.TOPICS.jsonName()).stream()
                .sorted(Comparator.comparing(topic -> topic.topicId().value()))
                .toList());
        observedAt = Objects.requireNonNull(observedAt, RegistryField.OBSERVED_AT.jsonName());
    }

    /**
     * Retrieve the state of a topic for a given topic ID.
     */
    public Optional<TopicState> findTopicState(TopicId topicId) {
        return topics.stream().filter(topic -> topic.topicId().equals(topicId)).findFirst();
    }
}
