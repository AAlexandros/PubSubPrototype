package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.util.HexUtils;

import java.nio.ByteBuffer;

import org.pubsub.prototype.constants.PersistenceConstants;
import static org.pubsub.prototype.util.validation.Validators.requireNonNegative;

/**
 * Derives deterministic keys for persisted events and per-topic publisher progress logs.
 * 
 * Event keys let a subscriber locate a missing event from its topic, publisher, and sequence
 * number without knowing the event contents, unlike an event ID derived from the contents.
 * 
 * Each topic has one log key, which routes its publishers' progress to the same replication servers.
 */
public final class EventKeys {
    private EventKeys() {
    }

    /** Derives the storage key from an event's topic, publisher public key, and sequence number. */
    public static String eventKey(EventEnvelope event) {
        return eventKey(event.topicId(), EventCrypto.publisherKeyId(event), event.sequenceNumber());
    }

    /**
     * Hashes the concatenation of the decoded 32-byte topic ID, decoded 32-byte publisher
     * key ID, and 8-byte sequence number. {@link ByteBuffer#putLong(long)} writes the
     * sequence number in the buffer's default big-endian byte order, so the input is exactly
     * 72 bytes and the returned SHA-256 key is a 64-character hexadecimal string.
     */
    public static String eventKey(String topicId, String publisherKeyId, long sequenceNumber) {
        requireNonNegative(sequenceNumber, PersistenceConstants.SEQUENCE_NUMBER_FIELD);
        ByteBuffer encoded = ByteBuffer.allocate(72);
        encoded.put(HexUtils.decodeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD));
        encoded.put(HexUtils.decodeSha256(publisherKeyId, PersistenceConstants.PUBLISHER_KEY_ID_FIELD));
        encoded.putLong(sequenceNumber);
        return EventCrypto.sha256Hex(encoded.array());
    }

    /**
     * Uses the normalized topic ID itself as the key for its publisher progress log.
     * Since the topic ID is already a unique 256-bit identifier, hashing it again is
     * unnecessary. All progress entries for the topic use this key when choosing replicas.
     */
    public static String topicLogKey(String topicId) {
        return HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD);
    }
}
