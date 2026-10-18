package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.auth.Authenticator;
import org.pubsub.prototype.auth.PublisherEventAuthenticator;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import static org.pubsub.prototype.util.validation.Validators.require;

public final class PersistenceEventValidator implements Authenticator<EventEnvelope, TopicState> {
    private final TopicStateProvider topics;
    private final Authenticator<EventEnvelope, String> publisher = new PublisherEventAuthenticator();

    public PersistenceEventValidator(TopicStateProvider topics) {
        this.topics = topics;
    }

    @Override
    public TopicState authenticate(EventEnvelope event) {
        TopicState topic = activeTopic(event.topicId());
        require(EventCrypto.eventId(event).equals(event.eventId()), "invalid event ID");
        String publisherId = publisher.authenticate(event);
        require(topic.publishers().isEmpty() || topic.publishers().contains(publisherId),
                "unauthorized publisher");
        return topic;
    }

    /** Retained for internal callers that still use the validator name. */
    public TopicState validate(EventEnvelope event) {
        return authenticate(event);
    }

    private TopicState activeTopic(String topicId) {
        TopicState topic = topics.topic(new TopicId(topicId))
                .orElseThrow(() -> new IllegalArgumentException("unknown topic"));
        require(topic.active(), "inactive topic");
        return topic;
    }
}
