package org.pubsub.prototype.replication;

import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

record ReplicationServerConfig(String serverId, String listenHost, String advertisedHost, int port,
                               Path storagePath, Path membershipPath, long membershipPollMs,
                               Path topicRegistryRuntimeDir, String topicRegistrySigner,
                               long requestTimeoutMs, long connectionTimeoutMs, int retries,
                               long cleanupIntervalMs, long epochZeroTimeMs, long epochLengthMs) {
    ReplicationServerConfig {
        serverId = PersistenceHex.require256(serverId, "serverId");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
        if (membershipPollMs < 1 || requestTimeoutMs < 1 || connectionTimeoutMs < 1
                || retries < 0 || cleanupIntervalMs < 1 || epochLengthMs < 1) {
            throw new IllegalArgumentException("invalid replication server timing configuration");
        }
    }

    ReplicationServer self() {
        return new ReplicationServer(serverId, advertisedHost, port);
    }

    @SuppressWarnings("unchecked")
    static ReplicationServerConfig load(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            Map<String, Object> root = new Yaml().load(input);
            Map<String, Object> server = (Map<String, Object>) root.get("server");
            Map<String, Object> registry = (Map<String, Object>) root.get("registry");
            Map<String, Object> timing = (Map<String, Object>) root.getOrDefault("timing", Map.of());
            Map<String, Object> epoch = (Map<String, Object>) root.getOrDefault("epoch", Map.of());
            return new ReplicationServerConfig(
                    required(server, "serverId"), required(server, "listenHost"), required(server, "advertisedHost"),
                    integer(server, "port", 0), Path.of(required(server, "storagePath")),
                    Path.of(required(registry, "membershipPath")), number(registry, "pollIntervalMs", 1000),
                    Path.of(required(registry, "topicRegistryRuntimeDir")), required(registry, "topicRegistrySigner"),
                    number(timing, "requestTimeoutMs", 3000), number(timing, "connectionTimeoutMs", 1000),
                    integer(timing, "retries", 1), number(timing, "cleanupIntervalMs", 5000),
                    number(epoch, "zeroTimeMs", 0), number(epoch, "lengthMs", 432_000_000));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read replication-server config " + file, ex);
        }
    }

    private static String required(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.toString();
    }

    private static long number(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.longValue() : value == null ? fallback : Long.parseLong(value.toString());
    }

    private static int integer(Map<String, Object> map, String key, int fallback) {
        return Math.toIntExact(number(map, key, fallback));
    }
}
