package org.pubsub.prototype.event;

import org.pubsub.prototype.registry.RegistryUnavailableException;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import java.util.Optional;

/**
 * Supplies topic state to event validation, authorization, and persistence code.
 *
 * <p>Production applications back this lookup with a periodically refreshed
 * topic registry observation; tests can provide a simple lookup. Registry-backed
 * lookups require a fresh observation and throw {@link RegistryUnavailableException}
 * when it is missing or stale.</p>
 */
public interface TopicStateProvider {

    /** Returns the state for a known topic, which may be inactive. */
    Optional<TopicState> findTopicState(TopicId topicId);
}
