package org.pubsub.prototype.registry.replication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

import static org.pubsub.prototype.constants.CryptoConstants.SHA_256_ALGORITHM;

/**
 * Lists the replication servers that are currently active. A Pub/Sub node uses
 * this list to find a server for storage and recovery requests. A replication
 * server uses it to decide which servers should hold event copies. Each entry
 * has a server ID, host, and port; the list can change between calls.
 */
@FunctionalInterface
public interface ReplicationMembership {
    /** Returns an immutable snapshot of the active servers and their addresses. */
    List<ReplicationServer> activeServers();

    /**
     * Returns a SHA-256 hash of the current server list. The hash includes each
     * server's ID, host, and port. Servers are sorted by ID, so list order has
     * no effect. Callers compare hashes to detect a membership change.
     */
    default String version() {
        String canonical = activeServers().stream()
                .sorted(java.util.Comparator.comparing(ReplicationServer::serverId))
                .map(server -> server.serverId() + "@" + server.host() + ":" + server.port())
                .collect(Collectors.joining("\n"));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256_ALGORITHM)
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
