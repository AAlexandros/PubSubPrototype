package org.pubsub.prototype.persistence;

import org.pubsub.prototype.event.EventEnvelope;

import java.time.Instant;
import static org.pubsub.prototype.util.PersistenceConstants.EVENT_ENVELOPE_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.EVENT_KEY_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.EXPIRES_AFTER_EPOCH_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.STORED_AT_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.STORED_AT_EPOCH_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_RETENTION_PERIOD_FIELD;
import static org.pubsub.prototype.util.Validators.requireNonNull;
import static org.pubsub.prototype.util.Validation.start;

public record StoredEvent(
        String eventKey,
        EventEnvelope eventEnvelope,
        Instant storedAt,
        long topicRetentionPeriod,
        long storedAtEpoch,
        long expiresAfterEpoch
) {
    public StoredEvent {
        eventKey = PersistenceHex.require256(eventKey, EVENT_KEY_FIELD);
        requireNonNull(eventEnvelope, EVENT_ENVELOPE_FIELD);
        requireNonNull(storedAt, STORED_AT_FIELD);
        start()
                .positive(topicRetentionPeriod, TOPIC_RETENTION_PERIOD_FIELD)
                .nonNegative(storedAtEpoch, STORED_AT_EPOCH_FIELD)
                .require(expiresAfterEpoch >= storedAtEpoch,
                        EXPIRES_AFTER_EPOCH_FIELD + " must not precede " + STORED_AT_EPOCH_FIELD)
                .throwIfInvalid();
    }
}
