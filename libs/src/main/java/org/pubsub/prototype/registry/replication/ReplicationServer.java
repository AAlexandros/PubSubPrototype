package org.pubsub.prototype.registry.replication;

import org.pubsub.prototype.util.HexUtils;

import java.net.URI;

import org.pubsub.prototype.constants.PersistenceConstants;
import static org.pubsub.prototype.util.validation.Validators.requireNonBlank;
import static org.pubsub.prototype.util.validation.Validators.requirePort;

public record ReplicationServer(String serverId, String host, int port) {
    public ReplicationServer {
        serverId = HexUtils.normalizeSha256(serverId, PersistenceConstants.SERVER_ID_FIELD);
        host = requireNonBlank(host, PersistenceConstants.HOST_FIELD);
        requirePort(port, PersistenceConstants.PORT_FIELD);
    }

    public URI uri(String path) {
        return URI.create("http://" + host + ":" + port + path);
    }
}
