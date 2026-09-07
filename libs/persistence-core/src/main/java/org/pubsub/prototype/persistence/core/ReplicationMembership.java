package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.persistence.ReplicationServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@FunctionalInterface
public interface ReplicationMembership {
    List<ReplicationServer> activeServers();

    default String version() {
        String canonical = activeServers().stream()
                .sorted(java.util.Comparator.comparing(ReplicationServer::serverId))
                .map(server -> server.serverId() + "@" + server.host() + ":" + server.port())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
