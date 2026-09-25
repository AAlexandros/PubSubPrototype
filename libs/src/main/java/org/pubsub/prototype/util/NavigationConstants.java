package org.pubsub.prototype.util;

/** Shared Navigation protocol constants and constructor argument names. */
public final class NavigationConstants {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_HOST_LENGTH = NetworkConstants.MAX_HOST_LENGTH;
    public static final int MIN_PORT = NetworkConstants.MIN_PORT;
    public static final int MAX_PORT = NetworkConstants.MAX_PORT;

    public static final String NODE_ID_ARGUMENT = SharedFieldNames.NODE_ID;
    public static final String HOST_ARGUMENT = SharedFieldNames.HOST;
    public static final String INITIAL_SUBSCRIPTIONS_ARGUMENT = "initialSubscriptions";
    public static final String RANDOM_ARGUMENT = "random";
    public static final String LOG_ARGUMENT = "log";
    public static final String SENDER_DESCRIPTOR_ARGUMENT = "senderDescriptor";
    public static final String SENDER_SUBSCRIPTIONS_ARGUMENT = "senderSubscriptions";
    public static final String CANDIDATES_ARGUMENT = "candidates";
    public static final String SUBSCRIBED_TOPIC_IDS_ARGUMENT = "subscribedTopicIds";
    public static final String SUBSCRIPTIONS_FIELD = "subscriptions";
    public static final String JSON_EXTENSION = ".json";
    public static final String LEGACY_TEXT_EXTENSION = ".txt";
    public static final String NODE_ID_PATTERN = CryptoConstants.SHA_256_HEX_PATTERN;
    public static final String HOST_PATTERN = NetworkConstants.HOST_PATTERN;
    public static final String UNSPECIFIED_IPV4_ADDRESS = NetworkConstants.UNSPECIFIED_IPV4_ADDRESS;
    public static final String UNSPECIFIED_IPV6_ADDRESS = NetworkConstants.UNSPECIFIED_IPV6_ADDRESS;
    public static final String INVALID_PEER_NODE_ID_MESSAGE = NetworkConstants.INVALID_PEER_NODE_ID_MESSAGE;
    public static final String INVALID_ADVERTISED_ENDPOINT_MESSAGE = NetworkConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;
    public static final String NEGATIVE_FRESHNESS_MESSAGE = NetworkConstants.NEGATIVE_FRESHNESS_MESSAGE;

    private NavigationConstants() {
    }
}
