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
public interface ReplicationMembershipProvider {
    /** Returns an immutable snapshot of the active servers and their addresses. */
    List<ReplicationServerEndpoint> activeServers();

    /**
     * Returns a SHA-256 hash of the current server list. The hash includes each
     * server's ID, host, and port. Servers are sorted by ID, so list order has
     * no effect. Callers compare hashes to detect a membership change.
     */
    default String version() {
        return versionOf(activeServers());
    }

    /** Returns the canonical membership version for a previously read snapshot. */
    static String versionOf(List<ReplicationServerEndpoint> servers) {
        String canonical = servers.stream()
                .sorted(java.util.Comparator.comparing(ReplicationServerEndpoint::serverId))
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
