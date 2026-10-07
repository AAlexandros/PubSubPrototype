package org.pubsub.prototype.persistence;

import java.util.List;

/** Reads the latest locally available replication-server registrations. */
public interface ReplicationRegistryReader {
    /**
     * Returns an immutable snapshot of registered server states as of this read.
     *
     * @param includeInactive whether deactivated registrations are included
     */
    List<ReplicationServerState> queryServers(boolean includeInactive);

    /** Returns an immutable snapshot of the endpoints of all currently active replication servers. */
    default List<ReplicationServer> activeServers() {
        return queryServers(false).stream().filter(ReplicationServerState::active)
                .map(ReplicationServerState::endpoint).toList();
    }
}
