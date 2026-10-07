package org.pubsub.prototype.persistence;

/** Client for signed operations that change Cardano replication-registry state. */
public interface ReplicationRegistryClient {
    /** Registers or updates a server using the client configuration's signing identity. */
    ReplicationServerState registerServer(ReplicationServerState server);

    /** Deactivates a registered server using the client configuration's signing identity. */
    void unregisterServer(String serverId);
}
