package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.persistence.ReplicationServer;

import java.util.List;

@FunctionalInterface
public interface ReplicationMembership {
    List<ReplicationServer> activeServers();
}
