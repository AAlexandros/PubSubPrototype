package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

public final class PersistenceEventValidator {
    private final TopicStateProvider topics;

    public PersistenceEventValidator(TopicStateProvider topics) {
        this.topics = topics;
    }

    public TopicState validate(EventEnvelope event) {
        TopicState topic = topic(event.topicId());
        if (!EventCrypto.eventId(event).equals(event.eventId())) throw new IllegalArgumentException("invalid event ID");
        if (!EventCrypto.verify(event)) throw new IllegalArgumentException("invalid event signature");
        String publisher = EventCrypto.publisherKeyId(event);
        if (!topic.publishers().isEmpty() && !topic.publishers().contains(publisher)) {
            throw new IllegalArgumentException("unauthorized publisher");
        }
        return topic;
    }

    public TopicState topic(String topicId) {
        TopicState topic = topics.topic(new TopicId(topicId))
                .orElseThrow(() -> new IllegalArgumentException("unknown topic"));
        if (!topic.active()) throw new IllegalArgumentException("inactive topic");
        return topic;
    }
}
