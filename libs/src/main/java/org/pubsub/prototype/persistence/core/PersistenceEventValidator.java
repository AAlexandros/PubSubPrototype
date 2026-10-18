package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import static org.pubsub.prototype.util.validation.Validators.require;

public final class PersistenceEventValidator {
    private final TopicStateProvider topics;

    public PersistenceEventValidator(TopicStateProvider topics) {
        this.topics = topics;
    }

    public TopicState validate(EventEnvelope event) {
        TopicState topic = activeTopic(event.topicId());
        require(EventCrypto.eventId(event).equals(event.eventId()), "invalid event ID");
        require(EventCrypto.verify(event), "invalid event signature");
        PublisherProgress.fromEvent(event);
        String publisher = EventCrypto.publisherKeyId(event);
        require(topic.publishers().isEmpty() || topic.publishers().contains(publisher),
                "unauthorized publisher");
        return topic;
    }

    private TopicState activeTopic(String topicId) {
        TopicState topic = topics.topic(new TopicId(topicId))
                .orElseThrow(() -> new IllegalArgumentException("unknown topic"));
        require(topic.active(), "inactive topic");
        return topic;
    }
}
