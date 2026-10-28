package org.pubsub.prototype.event;

import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import java.util.Optional;

public interface TopicStateProvider {
    /** Finds the currently observed state for a topic ID, when known. */
    Optional<TopicState> findTopicState(TopicId topicId);
}
