package org.pubsub.prototype.event;

import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.util.Optional;

public interface TopicStateProvider {
    Optional<TopicState> topic(TopicId topicId);
}
