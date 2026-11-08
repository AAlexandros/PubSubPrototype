package org.pubsub.prototype.registry.replication;

import java.net.URI;

import org.pubsub.prototype.constants.PersistenceConstants;
import org.pubsub.prototype.util.validation.Validators;
import static org.pubsub.prototype.constants.NetworkConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;

/**
 * The identity and advertised HTTP endpoint of one replication server.
 * Services use this value for replica placement and for requests to that server.
 * It contains only connection details; registry membership, operator, and
 * commitment state are represented by {@link ReplicationServerState}.
 *
 * @param serverId registered 256-bit server ID, normalized to lowercase hexadecimal
 * @param host advertised host used by other servers and Pub/Sub nodes; subject to the same
 *             advertised-address rules as {@link org.pubsub.prototype.sampling.NodeEndpoint}
 * @param port advertised HTTP port in the supported network range
 */
public record ReplicationServerEndpoint(String serverId, String host, int port) {
    /** Validates and normalizes the server ID, host, and port. */
    public ReplicationServerEndpoint {
        serverId = Validators.normalizeSha256Hex(serverId, PersistenceConstants.SERVER_ID_FIELD);
        Validators.require(Validators.isAdvertisedEndpoint(host, port), INVALID_ADVERTISED_ENDPOINT_MESSAGE);
    }

    /**
     * Builds an HTTP URI for this server by appending {@code path} to its host
     * and port. The caller supplies a path beginning with {@code /}, including
     * any query string; this method does not encode or normalize it.
     *
     * @param path absolute request path, optionally followed by a query string
     * @return URI for the requested resource on this server
     */
    public URI uri(String path) {
        return URI.create("http://" + host + ":" + port + path);
    }
}
