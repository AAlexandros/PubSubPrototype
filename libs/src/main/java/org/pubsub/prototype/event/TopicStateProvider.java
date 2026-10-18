package org.pubsub.prototype.event;

import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import java.util.Optional;

public interface TopicStateProvider {
    Optional<TopicState> topic(TopicId topicId);
}
