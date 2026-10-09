package org.pubsub.prototype.persistence;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.util.HexUtils;

import java.time.Instant;
import org.pubsub.prototype.util.PersistenceConstants;
import static org.pubsub.prototype.util.Validators.requireNonNull;
import static org.pubsub.prototype.util.Validation.start;

/**
 * An event replica with its lookup key and retention metadata.
 *
 * @param eventKey DHT lookup key derived from the topic ID, publisher key ID, and sequence number
 * @param eventEnvelope the published event and its protocol fields
 * @param storedAt wall-clock time recorded when the event was stored
 * @param topicRetentionPeriod the topic's retention period in epochs when the event was stored
 * @param storedAtEpoch the epoch when the event was stored
 * @param expiresAfterEpoch the first epoch at which the event is expired
 */
public record StoredEvent(
                String eventKey,
                EventEnvelope eventEnvelope,
                Instant storedAt,
                long topicRetentionPeriod,
                long storedAtEpoch,
                long expiresAfterEpoch) {

        public StoredEvent {

                eventKey = HexUtils.normalizeSha256(eventKey, PersistenceConstants.EVENT_KEY_FIELD);
                requireNonNull(eventEnvelope, PersistenceConstants.EVENT_ENVELOPE_FIELD);
                requireNonNull(storedAt, PersistenceConstants.STORED_AT_FIELD);
                start()
                        .positive(topicRetentionPeriod, PersistenceConstants.TOPIC_RETENTION_PERIOD_FIELD)
                        .nonNegative(storedAtEpoch, PersistenceConstants.STORED_AT_EPOCH_FIELD)
                        .require(expiresAfterEpoch >= storedAtEpoch,
                                        PersistenceConstants.EXPIRES_AFTER_EPOCH_FIELD + " must not precede "
                                                        + PersistenceConstants.STORED_AT_EPOCH_FIELD)
                        .throwIfInvalid();
        }
}
