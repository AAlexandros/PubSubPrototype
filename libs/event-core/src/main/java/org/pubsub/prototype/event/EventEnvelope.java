package org.pubsub.prototype.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

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
    public static final int VERSION = 1;

    public EventEnvelope {
        if (protocolVersion != VERSION) {
            throw new IllegalArgumentException("Unsupported event protocol version: " + protocolVersion);
        }
        EventHex.requireSha256(topicId, "topicId");
        Objects.requireNonNull(publisherPublicKey, "publisherPublicKey");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be non-negative");
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(signature, "signature");
        EventHex.requireSha256(eventId, "eventId");
    }

    public static EventEnvelope unsigned(String topicId, String publisherPublicKey, long sequenceNumber, long timestamp, String payload) {
        return new EventEnvelope(VERSION, topicId, publisherPublicKey, sequenceNumber, timestamp, payload, "", EventHex.zeroSha256());
    }

    public EventEnvelope withSignatureAndEventId(String newSignature, String newEventId) {
        return new EventEnvelope(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload, newSignature, newEventId);
    }

    public EventEnvelope withSignature(String newSignature) {
        return new EventEnvelope(protocolVersion, topicId, publisherPublicKey, sequenceNumber, timestamp, payload, newSignature, eventId);
    }
}
