package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import static org.pubsub.prototype.util.Validators.require;

public final class PersistenceEventValidator {
    private final TopicStateProvider topics;

    public PersistenceEventValidator(TopicStateProvider topics) {
        this.topics = topics;
    }

    public TopicState validate(EventEnvelope event) {
        TopicState topic = topic(event.topicId());
        require(EventCrypto.eventId(event).equals(event.eventId()), "invalid event ID");
        require(EventCrypto.verify(event), "invalid event signature");
        String publisher = EventCrypto.publisherKeyId(event);
        require(topic.publishers().isEmpty() || topic.publishers().contains(publisher),
                "unauthorized publisher");
        return topic;
    }

    public TopicState topic(String topicId) {
        TopicState topic = topics.topic(new TopicId(topicId))
                .orElseThrow(() -> new IllegalArgumentException("unknown topic"));
        require(topic.active(), "inactive topic");
        return topic;
    }
}
