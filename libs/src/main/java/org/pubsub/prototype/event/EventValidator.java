package org.pubsub.prototype.event;

import org.pubsub.prototype.auth.Authenticator;
import org.pubsub.prototype.auth.PublisherEventAuthenticator;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import java.util.Optional;

/**
 * Checks event integrity, publisher signatures, topic permission, and duplicate sequence use.
 * Basic envelope shape and field formats are checked when {@link EventEnvelope} is constructed.
 */
public final class EventValidator {
    private final TopicStateProvider topics;
    private final EventDeduplicator deduplicator;
    private final Authenticator<EventEnvelope, String> publisher = new PublisherEventAuthenticator();

    public EventValidator(TopicStateProvider topics, EventDeduplicator deduplicator) {
        this.topics = topics;
        this.deduplicator = deduplicator;
    }

    /**
     * Validates an event in this order:
     * <ol>
     *   <li>The topic ID is valid and identifies a known topic.</li>
     *   <li>The topic is active.</li>
     *   <li>The event ID matches a hash of the event's canonical contents.</li>
     *   <li>The event signature and publisher progress signature are valid. The publisher key ID
     *       is derived from the public key in the envelope.</li>
     *   <li>If the topic has a publisher allowlist, the key ID is on that list.</li>
     *   <li>Within the deduplication cache, the topic, publisher, and sequence number have not
     *       already been used for a different event. An exact repeat is reported as a duplicate;
     *       a different event at that sequence is rejected as an invalid sequence.</li>
     * </ol>
     * A topic with no publisher allowlist accepts any publisher with valid signatures. A new
     * accepted event is remembered in the bounded in-memory deduplication cache. This check does
     * not require sequence numbers to be contiguous or increasing.
     *
     * @return the publisher ID and accepted, duplicate, or rejection result
     */
    public EventValidationResult validate(EventEnvelope event) {
        TopicId topicId;
        try {
            topicId = new TopicId(event.topicId());
        } catch (RuntimeException ex) {
            return EventValidationResult.rejected(EventRejectReason.UNKNOWN_TOPIC, null, null);
        }
        Optional<TopicState> maybeTopic = topics.findTopicState(topicId);
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
        String publisherKeyId;
        try {
            publisherKeyId = publisher.authenticate(event);
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
