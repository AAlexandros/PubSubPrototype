package org.pubsub.prototype.persistence;

import org.pubsub.prototype.event.EventEnvelope;

import java.time.Instant;
import java.util.Objects;

public record StoredEvent(
        String eventKey,
        EventEnvelope eventEnvelope,
        Instant storedAt,
        long topicRetentionPeriod,
        long storedAtEpoch,
        long expiresAfterEpoch
) {
    public StoredEvent {
        eventKey = PersistenceHex.require256(eventKey, "eventKey");
        Objects.requireNonNull(eventEnvelope, "eventEnvelope");
        Objects.requireNonNull(storedAt, "storedAt");
        if (topicRetentionPeriod <= 0 || storedAtEpoch < 0 || expiresAfterEpoch < storedAtEpoch) {
            throw new IllegalArgumentException("invalid retention metadata");
        }
    }
}
