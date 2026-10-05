package org.pubsub.prototype.node.config;

/** Node YAML field paths and validation limits. */
final class NodeConfigConstants {
    static final int MINIMUM_POSITIVE_VALUE = 1;
    static final int MAX_OVERLAY_SIZE = 256;
    static final int MIN_ROUTING_BASE = 2;
    static final int MAX_ROUTING_BASE = 64;
    static final int MAX_AGE_THRESHOLD = 10_000;

    static final String PEER_SAMPLING_SECTION = "peerSampling";
    static final String NODE_NAME = "node.name";
    static final String NODE_LISTEN_HOST = "node.listenHost";
    static final String NODE_LISTEN_PORT = "node.listenPort";
    static final String NODE_IDENTITY_PATH = "node.identityPath";
    static final String PEERS = "peers";
    static final String PEER_HOST = "host";
    static final String PEER_PORT = "port";
    static final String CONTROL_HOST = "control.host";
    static final String CONTROL_PORT = "control.port";
    static final String REGISTRY_RUNTIME_DIR = "registry.runtimeDir";
    static final String REGISTRY_SIGNER = "registry.signer";
    static final String REGISTRY_CLI_BACKEND = "registry.cliBackend";
    static final String REGISTRY_POLL_INTERVAL_MS = "registry.pollIntervalMs";
    static final String SAMPLING_ADVERTISED_HOST = "peerSampling.advertisedHost";
    static final String SAMPLING_VIEW_SIZE = "peerSampling.viewSize";
    static final String SAMPLING_SWAP_LENGTH = "peerSampling.swapLength";
    static final String SAMPLING_CYCLE_INTERVAL_MS = "peerSampling.cycleIntervalMs";
    static final String SAMPLING_AGE_THRESHOLD = "peerSampling.ageThreshold";
    static final String SAMPLING_PROOF_FANOUT = "peerSampling.proofFanout";
    static final String SAMPLING_RANDOM_SEED = "peerSampling.randomSeed";
    static final String NAVIGATION_CAPACITY = "navigation.capacity";
    static final String NAVIGATION_ROUTING_BASE = "navigation.routingBase";
    static final String NAVIGATION_CYCLE_INTERVAL_MS = "navigation.cycleIntervalMs";
    static final String NAVIGATION_STALE_AFTER_MS = "navigation.staleAfterMs";
    static final String NAVIGATION_SUBSCRIPTIONS_PATH = "navigation.subscriptionsPath";
    static final String DISSEMINATION_RANDOM_LINK_COUNT = "dissemination.randomLinkCount";
    static final String DISSEMINATION_CYCLE_INTERVAL_MS = "dissemination.cycleIntervalMs";
    static final String DISSEMINATION_STALE_AFTER_MS = "dissemination.staleAfterMs";
    static final String DISSEMINATION_RANDOM_SEED = "dissemination.randomSeed";
    static final String PERSISTENCE_MEMBERSHIP_PATH = "persistence.membershipPath";
    static final String PERSISTENCE_DELIVERY_STATE_PATH = "persistence.deliveryStatePath";
    static final String PERSISTENCE_CONNECTION_TIMEOUT_MS = "persistence.connectionTimeoutMs";
    static final String PERSISTENCE_REQUEST_TIMEOUT_MS = "persistence.requestTimeoutMs";
    static final String PERSISTENCE_RETRIES = "persistence.retries";
    static final String PERSISTENCE_RECOVERY_CONCURRENCY = "persistence.recoveryConcurrency";

    private NodeConfigConstants() {
    }
}
