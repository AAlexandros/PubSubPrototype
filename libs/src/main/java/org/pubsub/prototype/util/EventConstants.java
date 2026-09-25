package org.pubsub.prototype.util;

/** Event wire-schema fields and storage names. */
public final class EventConstants {
    public static final int PROTOCOL_VERSION = 1;

    public static final String PROTOCOL_VERSION_FIELD = "protocolVersion";
    public static final String TOPIC_ID_FIELD = SharedFieldNames.TOPIC_ID;
    public static final String PUBLISHER_PUBLIC_KEY_FIELD = "publisherPublicKey";
    public static final String PUBLISHER_KEY_ID_FIELD = SharedFieldNames.PUBLISHER_KEY_ID;
    public static final String SEQUENCE_NUMBER_FIELD = SharedFieldNames.SEQUENCE_NUMBER;
    public static final String TIMESTAMP_FIELD = SharedFieldNames.TIMESTAMP;
    public static final String PAYLOAD_FIELD = "payload";
    public static final String SIGNATURE_FIELD = "signature";
    public static final String EVENT_ID_FIELD = SharedFieldNames.EVENT_ID;
    public static final String HEX_FIELD = "hex";
    public static final String MAX_ENTRIES_FIELD = "maxEntries";

    public static final String EVENTS_DIRECTORY = "events";
    public static final String EVENT_SEQUENCES_FILE = "event-sequences.json";

    private EventConstants() {
    }
}
