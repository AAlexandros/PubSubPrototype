package org.pubsub.prototype.cardano;

/** Environment names shared by Cardano command backends. */
public final class CardanoEnvironment {
    /** Cardano testnet identifier. */
    public static final String NETWORK_MAGIC = "CARDANO_NETWORK_MAGIC";
    /** Filesystem path to the Cardano node socket. */
    public static final String NODE_SOCKET_PATH = "CARDANO_NODE_SOCKET_PATH";
    /** Generated file containing the network environment. */
    public static final String NETWORK_FILE = "network.env";

    private CardanoEnvironment() {
    }
}
