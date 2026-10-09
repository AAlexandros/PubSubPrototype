package org.pubsub.prototype.persistence;

import org.pubsub.prototype.util.HexCodec;

import java.net.URI;

import static org.pubsub.prototype.util.PersistenceConstants.HOST_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.PORT_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requirePort;

public record ReplicationServer(String serverId, String host, int port) {
    public ReplicationServer {
        serverId = HexCodec.normalizeSha256(serverId, SERVER_ID_FIELD);
        host = requireNonBlank(host, HOST_FIELD);
        requirePort(port, PORT_FIELD);
    }

    public URI uri(String path) {
        return URI.create("http://" + host + ":" + port + path);
    }
}
