package org.pubsub.prototype.persistence;

import java.util.List;

public interface ReplicationRegistry {
    ReplicationServerState registerServer(ReplicationServerState server, String controllingOperator);
    void unregisterServer(String serverId, String controllingOperator);
    List<ReplicationServerState> queryServers(boolean includeInactive);

    default List<ReplicationServer> activeServers() {
        return queryServers(false).stream().filter(ReplicationServerState::active)
                .map(ReplicationServerState::member).toList();
    }
}
