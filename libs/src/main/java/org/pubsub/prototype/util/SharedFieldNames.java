package org.pubsub.prototype.util;

/** Field names used by more than one domain module or public API. */
public final class SharedFieldNames {
    public static final String VALUE = "value";
    public static final String NODE_ID = "nodeId";
    public static final String TOPIC_ID = "topicId";
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_KEY = "eventKey";
    public static final String PUBLISHER_KEY_ID = "publisherKeyId";
    public static final String SEQUENCE_NUMBER = "sequenceNumber";
    public static final String TIMESTAMP = "timestamp";
    public static final String FRESHNESS = "freshness";
    public static final String SERVER_ID = "serverId";
    public static final String HOST = "host";
    public static final String PORT = "port";

    private SharedFieldNames() {
    }
}
