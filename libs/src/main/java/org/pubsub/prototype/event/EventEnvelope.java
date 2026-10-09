package org.pubsub.prototype.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.pubsub.prototype.util.HexCodec;

import static org.pubsub.prototype.util.EventConstants.EVENT_ID_FIELD;
import static org.pubsub.prototype.util.EventConstants.PAYLOAD_FIELD;
import static org.pubsub.prototype.util.EventConstants.PROTOCOL_VERSION;
import static org.pubsub.prototype.util.EventConstants.PUBLISHER_PUBLIC_KEY_FIELD;
import static org.pubsub.prototype.util.EventConstants.SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.util.EventConstants.SIGNATURE_FIELD;
import static org.pubsub.prototype.util.EventConstants.TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.EventConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.util.Validators.require;
import static org.pubsub.prototype.util.Validators.requireNonNegative;
import static org.pubsub.prototype.util.Validators.requireNonNull;
import static org.pubsub.prototype.util.Validators.requireSha256Hex;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        int protocolVersion,
        String topicId,
        String publisherPublicKey,
        long sequenceNumber,
        long timestamp,
        String payload,
        String signature,
        String eventId
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
    }

    public static EventEnvelope unsigned(String topicId, String publisherPublicKey, long sequenceNumber, long timestamp, String payload) {
        return new EventEnvelope(PROTOCOL_VERSION, topicId, publisherPublicKey, sequenceNumber, timestamp,
                payload, "", HexCodec.zeroSha256());
    }

    public EventEnvelope withSignatureAndEventId(String newSignature, String newEventId) {
        return new EventEnvelope(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload, newSignature, newEventId);
    }

    public EventEnvelope withSignature(String newSignature) {
        return new EventEnvelope(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload, newSignature, eventId);
    }
}
