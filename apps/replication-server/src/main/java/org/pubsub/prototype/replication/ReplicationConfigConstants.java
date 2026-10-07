package org.pubsub.prototype.replication;

/** Field paths used by the replication-server YAML configuration. */
final class ReplicationConfigConstants {
    static final String SERVER_ID = "server.serverId";
    static final String LISTEN_HOST = "server.listenHost";
    static final String ADVERTISED_HOST = "server.advertisedHost";
    static final String PORT = "server.port";
    static final String STORAGE_PATH = "server.storagePath";
    static final String REGISTRATION_OPERATOR = "registration.operator";
    static final String COMMITMENT_START_EPOCH = "registration.commitmentStartEpoch";
    static final String COMMITMENT_END_EPOCH = "registration.commitmentEndEpoch";
    static final String REPLICATION_REGISTRY_CLI_BACKEND = "registration.cliBackend";
    static final String MEMBERSHIP_PATH = "registry.membershipPath";
    static final String POLL_INTERVAL_MS = "registry.pollIntervalMs";
    static final String TOPIC_REGISTRY_RUNTIME_DIR = "registry.topicRegistryRuntimeDir";
    static final String TOPIC_REGISTRY_SIGNER = "registry.topicRegistrySigner";
    static final String TOPIC_REGISTRY_CLI_BACKEND = "registry.topicRegistryCliBackend";
    static final String REQUEST_TIMEOUT_MS = "timing.requestTimeoutMs";
    static final String CONNECTION_TIMEOUT_MS = "timing.connectionTimeoutMs";
    static final String RETRIES = "timing.retries";
    static final String CLEANUP_INTERVAL_MS = "timing.cleanupIntervalMs";
    static final String FAILURE_PROBE_ATTEMPTS = "timing.failureProbeAttempts";
    static final String FAILURE_PROBE_TIMEOUT_MS = "timing.failureProbeTimeoutMs";
    static final String MAINTENANCE_INTERVAL_MS = "timing.maintenanceIntervalMs";
    static final String EPOCH_ZERO_TIME_MS = "epoch.zeroTimeMs";
    static final String EPOCH_LENGTH_MS = "epoch.lengthMs";

    private ReplicationConfigConstants() {
    }
}
