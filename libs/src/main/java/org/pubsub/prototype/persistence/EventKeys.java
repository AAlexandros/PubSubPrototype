package org.pubsub.prototype.persistence;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.util.HexCodec;

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
        encoded.put(HexCodec.decodeSha256(topicId, TOPIC_ID_FIELD));
        encoded.put(HexCodec.decodeSha256(publisherKeyId, PUBLISHER_KEY_ID_FIELD));
        encoded.putLong(sequenceNumber);
        return EventCrypto.sha256Hex(encoded.array());
    }

    public static String topicLogKey(String topicId) {
        return EventCrypto.sha256Hex(HexCodec.decodeSha256(topicId, TOPIC_ID_FIELD));
    }
}
