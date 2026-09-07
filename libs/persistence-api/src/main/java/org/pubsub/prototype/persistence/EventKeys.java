package org.pubsub.prototype.persistence;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;

import java.nio.ByteBuffer;

public final class EventKeys {
    private EventKeys() {
    }

    public static String eventKey(EventEnvelope event) {
        return eventKey(event.topicId(), EventCrypto.publisherKeyId(event), event.sequenceNumber());
    }

    public static String eventKey(String topicId, String publisherKeyId, long sequenceNumber) {
        if (sequenceNumber < 0) throw new IllegalArgumentException("sequenceNumber must be non-negative");
        ByteBuffer encoded = ByteBuffer.allocate(72);
        encoded.put(PersistenceHex.decode256(topicId, "topicId"));
        encoded.put(PersistenceHex.decode256(publisherKeyId, "publisherKeyId"));
        encoded.putLong(sequenceNumber);
        return EventCrypto.sha256Hex(encoded.array());
    }

    public static String topicLogKey(String topicId) {
        return EventCrypto.sha256Hex(PersistenceHex.decode256(topicId, "topicId"));
    }
}
