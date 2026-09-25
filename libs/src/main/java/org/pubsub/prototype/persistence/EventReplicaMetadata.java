package org.pubsub.prototype.persistence;

import static org.pubsub.prototype.util.PersistenceConstants.EVENT_KEY_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.EXPIRES_AFTER_EPOCH_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.PUBLISHER_KEY_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.STORED_AT_EPOCH_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_RETENTION_PERIOD_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.util.Validation.start;

public record EventReplicaMetadata(
        String eventKey,
        String topicId,
        String publisherKeyId,
        long sequenceNumber,
        long topicRetentionPeriod,
        long storedAtEpoch,
        long expiresAfterEpoch
) {
    public EventReplicaMetadata {
        eventKey = PersistenceHex.require256(eventKey, EVENT_KEY_FIELD);
        topicId = PersistenceHex.require256(topicId, TOPIC_ID_FIELD);
        publisherKeyId = PersistenceHex.require256(publisherKeyId, PUBLISHER_KEY_ID_FIELD);
        start()
                .nonNegative(sequenceNumber, SEQUENCE_NUMBER_FIELD)
                .positive(topicRetentionPeriod, TOPIC_RETENTION_PERIOD_FIELD)
                .nonNegative(storedAtEpoch, STORED_AT_EPOCH_FIELD)
                .require(expiresAfterEpoch >= storedAtEpoch,
                        EXPIRES_AFTER_EPOCH_FIELD + " must not precede " + STORED_AT_EPOCH_FIELD)
                .throwIfInvalid();
    }
}
