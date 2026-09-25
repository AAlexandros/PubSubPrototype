package org.pubsub.prototype.replication;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.util.Validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.pubsub.prototype.util.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.ADVERTISED_HOST;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.CLEANUP_INTERVAL_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.CONNECTION_TIMEOUT_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.EPOCH_LENGTH_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.EPOCH_ZERO_TIME_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.FAILURE_PROBE_ATTEMPTS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.FAILURE_PROBE_TIMEOUT_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.LISTEN_HOST;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.MAINTENANCE_INTERVAL_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.MEMBERSHIP_PATH;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.POLL_INTERVAL_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.PORT;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.REQUEST_TIMEOUT_MS;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.RETRIES;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.SERVER_ID;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.STORAGE_PATH;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.TOPIC_REGISTRY_RUNTIME_DIR;
import static org.pubsub.prototype.replication.ReplicationConfigConstants.TOPIC_REGISTRY_SIGNER;
import static org.pubsub.prototype.util.Validators.require;
import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

record ReplicationServerConfig(String serverId, String listenHost, String advertisedHost, int port,
                               Path storagePath, Path membershipPath, long membershipPollMs,
                               Path topicRegistryRuntimeDir, String topicRegistrySigner,
                               long requestTimeoutMs, long connectionTimeoutMs, int retries,
                               long cleanupIntervalMs, long epochZeroTimeMs, long epochLengthMs,
                               int failureProbeAttempts, long failureProbeTimeoutMs, long maintenanceIntervalMs) {
    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    ReplicationServerConfig {
        serverId = PersistenceHex.require256(serverId, SERVER_ID_FIELD);
        Validation.start()
                .port(port, PORT)
                .positive(membershipPollMs, POLL_INTERVAL_MS)
                .positive(requestTimeoutMs, REQUEST_TIMEOUT_MS)
                .positive(connectionTimeoutMs, CONNECTION_TIMEOUT_MS)
                .nonNegative(retries, RETRIES)
                .positive(cleanupIntervalMs, CLEANUP_INTERVAL_MS)
                .positive(epochLengthMs, EPOCH_LENGTH_MS)
                .positive(failureProbeAttempts, FAILURE_PROBE_ATTEMPTS)
                .positive(failureProbeTimeoutMs, FAILURE_PROBE_TIMEOUT_MS)
                .positive(maintenanceIntervalMs, MAINTENANCE_INTERVAL_MS)
                .throwIfInvalid();
    }

    ReplicationServer self() {
        return new ReplicationServer(serverId, advertisedHost, port);
    }

    static ReplicationServerConfig load(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return YAML_MAPPER.readValue(input, FileConfig.class).toConfig();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read replication-server config " + file, ex);
        }
    }

    private static final class FileConfig {
        public ServerSection server;
        public RegistrySection registry;
        public TimingSection timing;
        public EpochSection epoch;

        ReplicationServerConfig toConfig() {
            require(server != null && registry != null && timing != null && epoch != null,
                    "Config must define server, registry, timing, and epoch sections");
            return new ReplicationServerConfig(
                    requireNonBlank(server.serverId, SERVER_ID),
                    requireNonBlank(server.listenHost, LISTEN_HOST),
                    requireNonBlank(server.advertisedHost, ADVERTISED_HOST),
                    requireNonNull(server.port, PORT),
                    Path.of(requireNonBlank(server.storagePath, STORAGE_PATH)),
                    Path.of(requireNonBlank(registry.membershipPath, MEMBERSHIP_PATH)),
                    requireNonNull(registry.pollIntervalMs, POLL_INTERVAL_MS),
                    Path.of(requireNonBlank(registry.topicRegistryRuntimeDir, TOPIC_REGISTRY_RUNTIME_DIR)),
                    requireNonBlank(registry.topicRegistrySigner, TOPIC_REGISTRY_SIGNER),
                    requireNonNull(timing.requestTimeoutMs, REQUEST_TIMEOUT_MS),
                    requireNonNull(timing.connectionTimeoutMs, CONNECTION_TIMEOUT_MS),
                    requireNonNull(timing.retries, RETRIES),
                    requireNonNull(timing.cleanupIntervalMs, CLEANUP_INTERVAL_MS),
                    requireNonNull(epoch.zeroTimeMs, EPOCH_ZERO_TIME_MS),
                    requireNonNull(epoch.lengthMs, EPOCH_LENGTH_MS),
                    requireNonNull(timing.failureProbeAttempts, FAILURE_PROBE_ATTEMPTS),
                    requireNonNull(timing.failureProbeTimeoutMs, FAILURE_PROBE_TIMEOUT_MS),
                    requireNonNull(timing.maintenanceIntervalMs, MAINTENANCE_INTERVAL_MS));
        }
    }

    private static final class ServerSection {
        public String serverId;
        public String listenHost;
        public String advertisedHost;
        public Integer port;
        public String storagePath;
    }

    private static final class RegistrySection {
        public String membershipPath;
        public Long pollIntervalMs;
        public String topicRegistryRuntimeDir;
        public String topicRegistrySigner;
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

    private static final class EpochSection {
        public Long zeroTimeMs;
        public Long lengthMs;
    }
}
