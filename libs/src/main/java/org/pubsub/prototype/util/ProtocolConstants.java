package org.pubsub.prototype.util;

/** Transport protocol schema, identity storage, and field names. */
public final class ProtocolConstants {
    public static final int PROTOCOL_VERSION = 1;

    public static final String TYPE_FIELD = "type";
    public static final String NODE_ID_FIELD = SharedFieldNames.NODE_ID;
    public static final String NODE_NAME_FIELD = "nodeName";
    public static final String REQUEST_ID_FIELD = "requestId";
    public static final String SENT_AT_FIELD = "sentAt";
    public static final String EVENT_FIELD = "event";
    public static final String ENDPOINT_FIELD = "endpoint";
    public static final String EXCHANGE_FIELD = "exchange";
    public static final String NAVIGATION_FIELD = "navigation";
    public static final String DISSEMINATION_FIELD = "dissemination";
    public static final String PUBLIC_KEY_FIELD = "publicKey";
    public static final String PRIVATE_KEY_FIELD = "privateKey";
    public static final String VALUE_FIELD = SharedFieldNames.VALUE;

    public static final String IDENTITY_FILE = "identity.json";

    private ProtocolConstants() {
    }
}
