package org.pubsub.prototype.persistence;

import static org.pubsub.prototype.util.PersistenceConstants.LATEST_SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.LATEST_TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.PUBLISHER_KEY_ID_FIELD;
import static org.pubsub.prototype.util.Validation.start;

public record PublisherProgress(String publisherKeyId, long latestSequenceNumber, long latestTimestamp) {
    public PublisherProgress {
        publisherKeyId = PersistenceHex.require256(publisherKeyId, PUBLISHER_KEY_ID_FIELD);
        start()
                .nonNegative(latestSequenceNumber, LATEST_SEQUENCE_NUMBER_FIELD)
                .nonNegative(latestTimestamp, LATEST_TIMESTAMP_FIELD)
                .throwIfInvalid();
    }
}
