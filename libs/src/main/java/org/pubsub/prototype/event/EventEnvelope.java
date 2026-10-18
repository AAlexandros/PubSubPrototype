package org.pubsub.prototype.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.pubsub.prototype.util.HexUtils;

import static org.pubsub.prototype.constants.EventConstants.EVENT_ID_FIELD;
import static org.pubsub.prototype.constants.EventConstants.PAYLOAD_FIELD;
import static org.pubsub.prototype.constants.EventConstants.PROTOCOL_VERSION;
import static org.pubsub.prototype.constants.EventConstants.PUBLISHER_PUBLIC_KEY_FIELD;
import static org.pubsub.prototype.constants.EventConstants.SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.constants.EventConstants.SIGNATURE_FIELD;
import static org.pubsub.prototype.constants.EventConstants.TIMESTAMP_FIELD;
import static org.pubsub.prototype.constants.EventConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.util.validation.Validators.require;
import static org.pubsub.prototype.util.validation.Validators.requireNonNegative;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;
import static org.pubsub.prototype.util.validation.Validators.requireSha256Hex;

/** Signed event carrying a separate signature for its compact publisher-progress entry. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        int protocolVersion,
        String topicId,
        String publisherPublicKey,
        long sequenceNumber,
        long timestamp,
        String payload,
        String signature,
        String eventId,
        String progressSignature
) {
    public EventEnvelope {
        require(protocolVersion == PROTOCOL_VERSION, "Unsupported event protocol version: " + protocolVersion);
        requireSha256Hex(topicId, TOPIC_ID_FIELD);
        requireNonNull(publisherPublicKey, PUBLISHER_PUBLIC_KEY_FIELD);
        requireNonNegative(sequenceNumber, SEQUENCE_NUMBER_FIELD);
        requireNonNegative(timestamp, TIMESTAMP_FIELD);
        requireNonNull(payload, PAYLOAD_FIELD);
        requireNonNull(signature, SIGNATURE_FIELD);
        requireSha256Hex(eventId, EVENT_ID_FIELD);
        progressSignature = progressSignature == null ? "" : progressSignature;
    }

    public EventEnvelope(int protocolVersion, String topicId, String publisherPublicKey, long sequenceNumber,
                         long timestamp, String payload, String signature, String eventId) {
        this(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload, signature, eventId, "");
    }

    public static EventEnvelope unsigned(String topicId, String publisherPublicKey, long sequenceNumber, long timestamp, String payload) {
        return new EventEnvelope(PROTOCOL_VERSION, topicId, publisherPublicKey, sequenceNumber, timestamp,
                payload, "", HexUtils.zeroSha256());
    }

    public EventEnvelope withSignatureAndEventId(String newSignature, String newEventId) {
        return new EventEnvelope(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload,
                newSignature, newEventId, progressSignature);
    }

    public EventEnvelope withProgressSignature(String newProgressSignature) {
        return new EventEnvelope(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload,
                signature, eventId, newProgressSignature);
    }

    public EventEnvelope withSignature(String newSignature) {
        return new EventEnvelope(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload,
                newSignature, eventId, progressSignature);
    }
}
