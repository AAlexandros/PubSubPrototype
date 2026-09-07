package org.pubsub.prototype.persistence;

import java.net.URI;
import java.util.Objects;

public record ReplicationServer(String serverId, String host, int port) {
    public ReplicationServer {
        serverId = PersistenceHex.require256(serverId, "serverId");
        host = Objects.requireNonNull(host, "host");
        if (host.isBlank()) throw new IllegalArgumentException("host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
    }

    public URI uri(String path) {
        return URI.create("http://" + host + ":" + port + path);
    }
}
