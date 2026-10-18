package org.pubsub.prototype.replication.config;

/** Field paths and defaults used by the replication-server YAML configuration. */
public final class ReplicationConfigConstants {
    public static final String SERVER_ID = "server.serverId";
    public static final String LISTEN_HOST = "server.listenHost";
    public static final String ADVERTISED_HOST = "server.advertisedHost";
    public static final String PORT = "server.port";
    public static final String STORAGE_PATH = "server.storagePath";
    /** Optional server.httpWorkerThreads YAML setting; defaults to four workers. */
    public static final String HTTP_WORKER_THREADS = "server.httpWorkerThreads";
    public static final String REPAIR_SIGNING_KEY_PATH = "server.repairSigningKeyPath";
    public static final String REPAIR_VERIFICATION_KEY_PATH = "server.repairVerificationKeyPath";
    public static final int DEFAULT_HTTP_WORKER_THREADS = 4;
    public static final String REGISTRATION_OPERATOR = "registration.operator";
    public static final String COMMITMENT_START_EPOCH = "registration.commitmentStartEpoch";
    public static final String COMMITMENT_END_EPOCH = "registration.commitmentEndEpoch";
    public static final String REPLICATION_REGISTRY_CLI_BACKEND = "registration.cliBackend";
    public static final String MEMBERSHIP_PATH = "registry.membershipPath";
    public static final String POLL_INTERVAL_MS = "registry.pollIntervalMs";
    public static final String TOPIC_REGISTRY_RUNTIME_DIR = "registry.topicRegistryRuntimeDir";
    public static final String TOPIC_REGISTRY_SIGNER = "registry.topicRegistrySigner";
    public static final String TOPIC_REGISTRY_CLI_BACKEND = "registry.topicRegistryCliBackend";
    public static final String REQUEST_TIMEOUT_MS = "timing.requestTimeoutMs";
    public static final String CONNECTION_TIMEOUT_MS = "timing.connectionTimeoutMs";
    public static final String RETRIES = "timing.retries";
    public static final String CLEANUP_INTERVAL_MS = "timing.cleanupIntervalMs";
    public static final String FAILURE_PROBE_ATTEMPTS = "timing.failureProbeAttempts";
    public static final String FAILURE_PROBE_TIMEOUT_MS = "timing.failureProbeTimeoutMs";
    public static final String MAINTENANCE_INTERVAL_MS = "timing.maintenanceIntervalMs";

    private ReplicationConfigConstants() {
    }
}
