package org.pubsub.prototype.persistence;

import java.util.List;

public record ReplicaInventory(
        String serverId,
        String membershipVersion,
        List<EventReplicaMetadata> events,
        List<TopicLogReplicaMetadata> topicLogs
) {
    public ReplicaInventory {
        serverId = PersistenceHex.require256(serverId, "serverId");
        if (membershipVersion == null || membershipVersion.isBlank()) {
            throw new IllegalArgumentException("membershipVersion is required");
        }
        events = List.copyOf(events);
        topicLogs = List.copyOf(topicLogs);
    }
}
