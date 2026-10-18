package org.pubsub.prototype.persistence;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.util.HexUtils;
import org.pubsub.prototype.constants.PersistenceConstants;
import org.pubsub.prototype.util.validation.Validation;

import java.util.Base64;

import static org.pubsub.prototype.util.validation.Validators.require;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;

/**
 * The latest published-event progress for one publisher in a topic log.
 *
 * @param topicId the topic whose progress is signed
 * @param publisherPublicKey the publisher's Ed25519 public key
 * @param publisherKeyId the publisher's public-key identifier
 * @param latestSequenceNumber the sequence number of the publisher's latest event
 * @param latestTimestamp the timestamp of that event, used to find publishers active since a subscriber went offline
 * @param signature the publisher's signature over the topic, public key, sequence, and timestamp
 */
public record PublisherProgress(String topicId, String publisherPublicKey, String publisherKeyId,
                                long latestSequenceNumber, long latestTimestamp, String signature) {

    public PublisherProgress {
        topicId = HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD);
        requireNonNull(publisherPublicKey, "publisherPublicKey");
        publisherKeyId = HexUtils.normalizeSha256(publisherKeyId, PersistenceConstants.PUBLISHER_KEY_ID_FIELD);
        Validation.start()
                .nonNegative(latestSequenceNumber, PersistenceConstants.LATEST_SEQUENCE_NUMBER_FIELD)
                .nonNegative(latestTimestamp, PersistenceConstants.LATEST_TIMESTAMP_FIELD)
                .throwIfInvalid();
        requireNonNull(signature, "signature");
        byte[] encodedPublicKey;
        try {
            encodedPublicKey = Base64.getDecoder().decode(publisherPublicKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid publisher public key", ex);
        }
        require(EventCrypto.sha256Hex(encodedPublicKey).equals(publisherKeyId),
                "publisher key ID does not match public key");
        require(EventCrypto.verifyProgress(topicId, publisherPublicKey, latestSequenceNumber, latestTimestamp,
                signature), "invalid publisher progress signature");
    }

    public static PublisherProgress fromEvent(EventEnvelope event) {
        return new PublisherProgress(event.topicId(), event.publisherPublicKey(), EventCrypto.publisherKeyId(event),
                event.sequenceNumber(), event.timestamp(), event.progressSignature());
    }
}
