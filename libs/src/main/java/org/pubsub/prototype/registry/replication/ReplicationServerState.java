package org.pubsub.prototype.registry.replication;

import java.util.Objects;

/**
 * A replication-server registration as stored in, or observed from, the registry.
 *
 * <p>This is registry state, not this process's local configuration. A server's operator submits
 * its configured endpoint and commitment when registering; consumers then obtain states for all
 * servers from the registry.</p>
 *
 * @param endpoint the server's permanent identifier and advertised HTTP address
 * @param operator the Cardano account responsible for and authorized to change this registration
 * @param commitmentStartEpoch the first Cardano epoch covered by the service commitment
 * @param commitmentEndEpoch the last Cardano epoch covered by the service commitment
 * @param active whether the server is an active member of the replication-server set
 */
public record ReplicationServerState(
        ReplicationServerEndpoint endpoint,
        String operator,
        long commitmentStartEpoch,
        long commitmentEndEpoch,
        boolean active
) {
    public ReplicationServerState {
        Objects.requireNonNull(endpoint, "endpoint");
    }

    /**
     * Returns the registered server identifier.
     */
    public String serverId() {
        return endpoint.serverId();
    }

    /** Returns the registered HTTP host. */
    public String host() {
        return endpoint.host();
    }

    /** Returns the registered HTTP port. */
    public int port() {
        return endpoint.port();
    }

    /**
     * Returns this registration as an inactive tombstone, preserving its identity and commitment.
     * Authorization of the unregistration is performed by the registry.
     */
    public ReplicationServerState deactivate() {
        return new ReplicationServerState(endpoint, operator,
                commitmentStartEpoch, commitmentEndEpoch, false);
    }
}
