package org.pubsub.prototype.persistence.maintenance;

import org.pubsub.prototype.util.HexUtils;

import org.pubsub.prototype.constants.PersistenceConstants;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.util.validation.Validation;

import static org.pubsub.prototype.util.validation.Validators.require;

/**
 * Identifying and retention metadata for an event replica, used to compare
 * inventories without transferring the event payload.
 *
 * @param eventKey             DHT lookup key for the event
 * @param topicId              ID of the topic that contains the event
 * @param publisherKeyId       ID of the publisher that signed the event
 * @param sequenceNumber       publisher's sequence number for the event
 * @param topicRetentionPeriod current topic retention period applied to the event, in epochs
 * @param storedAtEpoch        epoch when the event was stored
 * @param expiresAfterEpoch    first expired epoch under the applied retention period
 */
public record EventReplicaMetadata(
                String eventKey,
                String topicId,
                String publisherKeyId,
                long sequenceNumber,
                long topicRetentionPeriod,
                long storedAtEpoch,
                long expiresAfterEpoch) {

        public EventReplicaMetadata {
                eventKey = HexUtils.normalizeSha256(eventKey, PersistenceConstants.EVENT_KEY_FIELD);
                topicId = HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD);
                publisherKeyId = HexUtils.normalizeSha256(publisherKeyId, PersistenceConstants.PUBLISHER_KEY_ID_FIELD);

                Validation.start()
                                .nonNegative(sequenceNumber, PersistenceConstants.SEQUENCE_NUMBER_FIELD)
                                .positive(topicRetentionPeriod, PersistenceConstants.TOPIC_RETENTION_PERIOD_FIELD)
                                .nonNegative(storedAtEpoch, PersistenceConstants.STORED_AT_EPOCH_FIELD)
                                .require(expiresAfterEpoch >= storedAtEpoch,
                                                PersistenceConstants.EXPIRES_AFTER_EPOCH_FIELD + " must not precede "
                                                                + PersistenceConstants.STORED_AT_EPOCH_FIELD)
                                .throwIfInvalid();
                require(eventKey.equals(EventKeys.eventKey(topicId, publisherKeyId, sequenceNumber)),
                                PersistenceConstants.EVENT_KEY_FIELD + " does not match the event identity");
        }
}
