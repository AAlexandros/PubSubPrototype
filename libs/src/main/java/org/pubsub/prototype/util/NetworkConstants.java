package org.pubsub.prototype.util;

/** Network endpoint limits shared by advertised peer descriptors. */
public final class NetworkConstants {
    public static final int MAX_HOST_LENGTH = 253;
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65_535;

    public static final String HOST_PATTERN = "[a-zA-Z0-9.:-]+";
    public static final String UNSPECIFIED_IPV4_ADDRESS = "0.0.0.0";
    public static final String UNSPECIFIED_IPV6_ADDRESS = "::";
    public static final String INVALID_PEER_NODE_ID_MESSAGE = "Invalid peer nodeId";
    public static final String INVALID_ADVERTISED_ENDPOINT_MESSAGE = "Invalid advertised endpoint";
    public static final String NEGATIVE_FRESHNESS_MESSAGE = "freshness must be non-negative";

    private NetworkConstants() {
    }
}
