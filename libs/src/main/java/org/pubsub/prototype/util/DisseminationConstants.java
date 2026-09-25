package org.pubsub.prototype.util;

/** Dissemination protocol fields, limits, and validation messages. */
public final class DisseminationConstants {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_RANDOM_LINK_COUNT = 256;

    public static final String NODE_ID_FIELD = SharedFieldNames.NODE_ID;
    public static final String HOST_FIELD = SharedFieldNames.HOST;
    public static final String RANDOM_FIELD = "random";
    public static final String LOG_FIELD = "log";
    public static final String TOPIC_ID_FIELD = SharedFieldNames.TOPIC_ID;
    public static final String SENDER_DESCRIPTOR_FIELD = "senderDescriptor";
    public static final String CANDIDATES_FIELD = "candidates";
    public static final String SUBSCRIBED_TOPIC_IDS_FIELD = "subscribedTopicIds";

    public static final String INVALID_PEER_NODE_ID_MESSAGE = NetworkConstants.INVALID_PEER_NODE_ID_MESSAGE;
    public static final String INVALID_ADVERTISED_ENDPOINT_MESSAGE = NetworkConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;
    public static final String NEGATIVE_FRESHNESS_MESSAGE = NetworkConstants.NEGATIVE_FRESHNESS_MESSAGE;
    public static final String INVALID_TOPIC_ID_MESSAGE = "Invalid topicId";

    private DisseminationConstants() {
    }
}
