package org.pubsub.prototype.persistence;

/**
 * A replication-server registration as stored in, or observed from, the registry.
 *
 * <p>This is registry state, not this process's local configuration. A server's operator submits
 * its configured endpoint and commitment when registering; consumers then obtain states for all
 * servers from the registry.</p>
 *
 * @param serverId the permanent identifier of the replication server
 * @param operator the Cardano account responsible for and authorized to change this registration
 * @param host the server's advertised network host
 * @param port the server's advertised network port
 * @param commitmentStartEpoch the first Cardano epoch covered by the service commitment
 * @param commitmentEndEpoch the last Cardano epoch covered by the service commitment
 * @param active whether the server is an active member of the replication-server set
 */
public record ReplicationServerState(
        String serverId,
        String operator,
        String host,
        int port,
        long commitmentStartEpoch,
        long commitmentEndEpoch,
        boolean active
) {

    /**
     * Returns the endpoint information needed to contact this server.
     */
    public ReplicationServer endpoint() {
        return new ReplicationServer(serverId, host, port);
    }

    /**
     * Returns this registration as an inactive tombstone, preserving its identity and commitment.
     * Authorization of the unregistration is performed by the registry.
     */
    public ReplicationServerState deactivate() {
        return new ReplicationServerState(serverId, operator, host, port,
                commitmentStartEpoch, commitmentEndEpoch, false);
    }
}
