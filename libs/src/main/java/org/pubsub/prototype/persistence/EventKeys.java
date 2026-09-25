package org.pubsub.prototype.persistence;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;

import java.nio.ByteBuffer;

import static org.pubsub.prototype.util.PersistenceConstants.PUBLISHER_KEY_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.util.Validators.requireNonNegative;

public final class EventKeys {
    private EventKeys() {
    }

    public static String eventKey(EventEnvelope event) {
        return eventKey(event.topicId(), EventCrypto.publisherKeyId(event), event.sequenceNumber());
    }

    public static String eventKey(String topicId, String publisherKeyId, long sequenceNumber) {
        requireNonNegative(sequenceNumber, SEQUENCE_NUMBER_FIELD);
        ByteBuffer encoded = ByteBuffer.allocate(72);
        encoded.put(PersistenceHex.decode256(topicId, TOPIC_ID_FIELD));
        encoded.put(PersistenceHex.decode256(publisherKeyId, PUBLISHER_KEY_ID_FIELD));
        encoded.putLong(sequenceNumber);
        return EventCrypto.sha256Hex(encoded.array());
    }

    public static String topicLogKey(String topicId) {
        return EventCrypto.sha256Hex(PersistenceHex.decode256(topicId, TOPIC_ID_FIELD));
    }
}
