package org.pubsub.prototype.replication.config;

import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.registry.replication.ReplicationServer;
import org.pubsub.prototype.util.HexUtils;
import org.pubsub.prototype.util.files.YamlFiles;
import org.pubsub.prototype.util.validation.Validation;

import java.nio.file.Path;

import static org.pubsub.prototype.constants.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.ADVERTISED_HOST;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.CLEANUP_INTERVAL_MS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.COMMITMENT_END_EPOCH;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.COMMITMENT_START_EPOCH;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.CONNECTION_TIMEOUT_MS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.DEFAULT_HTTP_WORKER_THREADS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.FAILURE_PROBE_ATTEMPTS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.FAILURE_PROBE_TIMEOUT_MS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.HTTP_WORKER_THREADS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.LISTEN_HOST;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.MAINTENANCE_INTERVAL_MS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.POLL_INTERVAL_MS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.PORT;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.REGISTRATION_OPERATOR;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.REPLICATION_REGISTRY_CLI_BACKEND;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.REPLICATION_REGISTRY_RUNTIME_DIR;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.REGISTRY_MAXIMUM_AGE_MS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.REQUEST_TIMEOUT_MS;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.REQUEST_SIGNING_KEY_PATH;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.REQUEST_VERIFICATION_KEY_PATH;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.RETRIES;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.SERVER_ID;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.STORAGE_PATH;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.TOPIC_REGISTRY_CLI_BACKEND;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.TOPIC_REGISTRY_RUNTIME_DIR;
import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.TOPIC_REGISTRY_SIGNER;
import static org.pubsub.prototype.util.validation.Validators.require;
import static org.pubsub.prototype.util.validation.Validators.requireNonBlank;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;

public record ReplicationServerConfig(String serverId, String listenHost, String advertisedHost, int port,
                               int httpWorkerThreads,
                               Path storagePath, Path requestSigningKeyPath, Path requestVerificationKeyPath,
                               Path replicationRegistryRuntimeDir, long membershipPollMs, long registryMaximumAgeMs,
                               Registration registration,
                               Path topicRegistryRuntimeDir, String topicRegistrySigner,
                               CardanoCliBackend topicRegistryCliBackend,
                               long requestTimeoutMs, long connectionTimeoutMs, int retries,
                               long cleanupIntervalMs,
                               int failureProbeAttempts, long failureProbeTimeoutMs, long maintenanceIntervalMs) {
    public ReplicationServerConfig {
        serverId = HexUtils.normalizeSha256(serverId, SERVER_ID_FIELD);
        requireNonNull(requestSigningKeyPath, REQUEST_SIGNING_KEY_PATH);
        requireNonNull(requestVerificationKeyPath, REQUEST_VERIFICATION_KEY_PATH);
        Validation.start()
                .port(port, PORT)
                .positive(httpWorkerThreads, HTTP_WORKER_THREADS)
                .positive(membershipPollMs, POLL_INTERVAL_MS)
                .positive(registryMaximumAgeMs, REGISTRY_MAXIMUM_AGE_MS)
                .positive(requestTimeoutMs, REQUEST_TIMEOUT_MS)
                .positive(connectionTimeoutMs, CONNECTION_TIMEOUT_MS)
                .nonNegative(retries, RETRIES)
                .positive(cleanupIntervalMs, CLEANUP_INTERVAL_MS)
                .positive(failureProbeAttempts, FAILURE_PROBE_ATTEMPTS)
                .positive(failureProbeTimeoutMs, FAILURE_PROBE_TIMEOUT_MS)
                .positive(maintenanceIntervalMs, MAINTENANCE_INTERVAL_MS)
                .throwIfInvalid();
    }

    public ReplicationServer self() {
        return new ReplicationServer(serverId, advertisedHost, port);
    }

    /** Loads and validates the replication-server configuration from YAML. */
    public static ReplicationServerConfig load(Path file) {
        return read(file).toConfig();
    }

    /** Loads just the YAML values used by the standalone registry-management command. */
    public static RegistryRegistration loadRegistryRegistration(Path file) {
        return read(file).toRegistryRegistration();
    }

    private static FileConfig read(Path file) {
        return YamlFiles.read(file, FileConfig.class);
    }

    private static final class FileConfig {
        public ServerSection server;
        public RegistrationSection registration;
        public RegistrySection registry;
        public TimingSection timing;

        ReplicationServerConfig toConfig() {
            require(server != null && registration != null && registry != null && timing != null,
                    "Config must define server, registration, registry, and timing sections");
            return new ReplicationServerConfig(
                    requireNonBlank(server.serverId, SERVER_ID),
                    requireNonBlank(server.listenHost, LISTEN_HOST),
                    requireNonBlank(server.advertisedHost, ADVERTISED_HOST),
                    requireNonNull(server.port, PORT),
                    server.httpWorkerThreads == null ? DEFAULT_HTTP_WORKER_THREADS
                            : requireNonNull(server.httpWorkerThreads, HTTP_WORKER_THREADS),
                    Path.of(requireNonBlank(server.storagePath, STORAGE_PATH)),
                    Path.of(requireNonBlank(server.requestSigningKeyPath, REQUEST_SIGNING_KEY_PATH)),
                    Path.of(requireNonBlank(server.requestVerificationKeyPath, REQUEST_VERIFICATION_KEY_PATH)),
                    Path.of(requireNonBlank(registry.replicationRuntimeDir, REPLICATION_REGISTRY_RUNTIME_DIR)),
                    requireNonNull(registry.pollIntervalMs, POLL_INTERVAL_MS),
                    requireNonNull(registry.maximumAgeMs, REGISTRY_MAXIMUM_AGE_MS),
                    new Registration(requireNonBlank(registration.operator, REGISTRATION_OPERATOR),
                            requireNonNull(registration.commitmentStartEpoch, COMMITMENT_START_EPOCH),
                            requireNonNull(registration.commitmentEndEpoch, COMMITMENT_END_EPOCH),
                            requireNonNull(registration.cliBackend, REPLICATION_REGISTRY_CLI_BACKEND)),
                    Path.of(requireNonBlank(registry.topicRegistryRuntimeDir, TOPIC_REGISTRY_RUNTIME_DIR)),
                    requireNonBlank(registry.topicRegistrySigner, TOPIC_REGISTRY_SIGNER),
                    requireNonNull(registry.topicRegistryCliBackend, TOPIC_REGISTRY_CLI_BACKEND),
                    requireNonNull(timing.requestTimeoutMs, REQUEST_TIMEOUT_MS),
                    requireNonNull(timing.connectionTimeoutMs, CONNECTION_TIMEOUT_MS),
                    requireNonNull(timing.retries, RETRIES),
                    requireNonNull(timing.cleanupIntervalMs, CLEANUP_INTERVAL_MS),
                    requireNonNull(timing.failureProbeAttempts, FAILURE_PROBE_ATTEMPTS),
                    requireNonNull(timing.failureProbeTimeoutMs, FAILURE_PROBE_TIMEOUT_MS),
                    requireNonNull(timing.maintenanceIntervalMs, MAINTENANCE_INTERVAL_MS));
        }

        RegistryRegistration toRegistryRegistration() {
            require(server != null && registration != null && registry != null && timing != null,
                    "Config must define server, registration, registry, and timing sections");
            return new RegistryRegistration(
                    requireNonBlank(server.serverId, SERVER_ID),
                    requireNonBlank(server.advertisedHost, ADVERTISED_HOST),
                    requireNonNull(server.port, PORT),
                    new Registration(requireNonBlank(registration.operator, REGISTRATION_OPERATOR),
                            requireNonNull(registration.commitmentStartEpoch, COMMITMENT_START_EPOCH),
                            requireNonNull(registration.commitmentEndEpoch, COMMITMENT_END_EPOCH),
                            requireNonNull(registration.cliBackend, REPLICATION_REGISTRY_CLI_BACKEND)));
        }
    }

    private static final class ServerSection {
        public String serverId;
        public String listenHost;
        public String advertisedHost;
        public Integer port;
        public Integer httpWorkerThreads;
        public String storagePath;
        public String requestSigningKeyPath;
        public String requestVerificationKeyPath;
    }

    private static final class RegistrationSection {
        public String operator;
        public Long commitmentStartEpoch;
        public Long commitmentEndEpoch;
        public CardanoCliBackend cliBackend;
    }

    public record Registration(String operator, long commitmentStartEpoch, long commitmentEndEpoch,
                        CardanoCliBackend cliBackend) {
        public Registration {
            Validation.start()
                    .nonNegative(commitmentStartEpoch, COMMITMENT_START_EPOCH)
                    .nonNegative(commitmentEndEpoch, COMMITMENT_END_EPOCH)
                    .throwIfInvalid();
            require(commitmentEndEpoch >= commitmentStartEpoch,
                    "invalid " + COMMITMENT_START_EPOCH + " and " + COMMITMENT_END_EPOCH + " range");
        }
    }

    /** YAML values needed by the standalone registry-management command. */
    public record RegistryRegistration(String serverId, String advertisedHost, int port, Registration registration) {
        public RegistryRegistration {
            serverId = HexUtils.normalizeSha256(serverId, SERVER_ID_FIELD);
            advertisedHost = requireNonBlank(advertisedHost, ADVERTISED_HOST);
            registration = requireNonNull(registration, "registration");
            Validation.start().port(port, PORT).throwIfInvalid();
        }
    }

    private static final class RegistrySection {
        public String replicationRuntimeDir;
        public Long pollIntervalMs;
        public Long maximumAgeMs;
        public String topicRegistryRuntimeDir;
        public String topicRegistrySigner;
        public CardanoCliBackend topicRegistryCliBackend;
    }

    private static final class TimingSection {
        public Long requestTimeoutMs;
        public Long connectionTimeoutMs;
        public Integer retries;
        public Long cleanupIntervalMs;
        public Integer failureProbeAttempts;
        public Long failureProbeTimeoutMs;
        public Long maintenanceIntervalMs;
    }

}
