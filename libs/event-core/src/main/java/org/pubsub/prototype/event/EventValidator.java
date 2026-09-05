package org.pubsub.prototype.event;

import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.util.Optional;

public final class EventValidator {
    private final TopicStateProvider topics;
    private final EventDeduplicator deduplicator;

    public EventValidator(TopicStateProvider topics, EventDeduplicator deduplicator) {
        this.topics = topics;
        this.deduplicator = deduplicator;
    }

    public EventValidationResult validate(EventEnvelope event) {
        TopicId topicId;
        try {
            topicId = new TopicId(event.topicId());
        } catch (RuntimeException ex) {
            return EventValidationResult.rejected(EventRejectReason.UNKNOWN_TOPIC, null, null);
        }
        Optional<TopicState> maybeTopic = topics.topic(topicId);
        if (maybeTopic.isEmpty()) {
            return EventValidationResult.rejected(EventRejectReason.UNKNOWN_TOPIC, null, null);
        }
        TopicState topic = maybeTopic.get();
        if (!topic.active()) {
            return EventValidationResult.rejected(EventRejectReason.INACTIVE_TOPIC, null, null);
        }
        if (!eventIdMatches(event)) {
            return EventValidationResult.rejected(EventRejectReason.INVALID_EVENT_ID, null, null);
        }
        if (!EventCrypto.verify(event)) {
            return EventValidationResult.rejected(EventRejectReason.INVALID_SIGNATURE, null, null);
        }
        String publisherKeyId;
        try {
            publisherKeyId = EventCrypto.publisherKeyId(event);
        } catch (RuntimeException ex) {
            return EventValidationResult.rejected(EventRejectReason.INVALID_SIGNATURE, null, null);
        }
        if (!topic.publishers().isEmpty() && !topic.publishers().contains(publisherKeyId)) {
            return EventValidationResult.rejected(EventRejectReason.UNAUTHORIZED_PUBLISHER, publisherKeyId, null);
        }
        EventSequenceStatus status = deduplicator.checkAndRemember(event, publisherKeyId);
        if (status == EventSequenceStatus.DUPLICATE) {
            return EventValidationResult.duplicate(publisherKeyId);
        }
        if (status == EventSequenceStatus.CONFLICT) {
            return EventValidationResult.rejected(EventRejectReason.INVALID_SEQUENCE, publisherKeyId, status);
        }
        return EventValidationResult.accepted(publisherKeyId);
    }

    private static boolean eventIdMatches(EventEnvelope event) {
        try {
            return EventCrypto.eventId(event).equals(event.eventId());
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
