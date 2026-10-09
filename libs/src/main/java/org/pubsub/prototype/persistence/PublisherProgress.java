package org.pubsub.prototype.persistence;

import org.pubsub.prototype.util.HexUtils;
import org.pubsub.prototype.util.PersistenceConstants;
import org.pubsub.prototype.util.Validation;

/**
 * The latest published-event progress for one publisher in a topic log.
 *
 * @param publisherKeyId the publisher's public-key identifier
 * @param latestSequenceNumber the sequence number of the publisher's latest event
 * @param latestTimestamp the timestamp of that event, used to find publishers active since a subscriber went offline
 */
public record PublisherProgress(String publisherKeyId, long latestSequenceNumber, long latestTimestamp) {

    public PublisherProgress {

        publisherKeyId = HexUtils.normalizeSha256(publisherKeyId, PersistenceConstants.PUBLISHER_KEY_ID_FIELD);
        Validation.start()
                .nonNegative(latestSequenceNumber, PersistenceConstants.LATEST_SEQUENCE_NUMBER_FIELD)
                .nonNegative(latestTimestamp, PersistenceConstants.LATEST_TIMESTAMP_FIELD)
                .throwIfInvalid();
    }

}
