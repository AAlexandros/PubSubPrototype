package org.pubsub.prototype.persistence.maintenance;

import org.pubsub.prototype.util.HexUtils;

import static org.pubsub.prototype.constants.PersistenceConstants.EVENT_KEY_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.EXPIRES_AFTER_EPOCH_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.PUBLISHER_KEY_ID_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.STORED_AT_EPOCH_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.TOPIC_RETENTION_PERIOD_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.util.validation.Validation.start;

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
        eventKey = HexUtils.normalizeSha256(eventKey, EVENT_KEY_FIELD);
        topicId = HexUtils.normalizeSha256(topicId, TOPIC_ID_FIELD);
        publisherKeyId = HexUtils.normalizeSha256(publisherKeyId, PUBLISHER_KEY_ID_FIELD);
        start()
                .nonNegative(sequenceNumber, SEQUENCE_NUMBER_FIELD)
                .positive(topicRetentionPeriod, TOPIC_RETENTION_PERIOD_FIELD)
                .nonNegative(storedAtEpoch, STORED_AT_EPOCH_FIELD)
                .require(expiresAfterEpoch >= storedAtEpoch,
                        EXPIRES_AFTER_EPOCH_FIELD + " must not precede " + STORED_AT_EPOCH_FIELD)
                .throwIfInvalid();
    }
}
