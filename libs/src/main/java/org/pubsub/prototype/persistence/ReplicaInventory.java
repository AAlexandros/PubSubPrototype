package org.pubsub.prototype.persistence;

import org.pubsub.prototype.util.HexCodec;

import java.util.List;

import static org.pubsub.prototype.util.PersistenceConstants.EVENTS_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.MEMBERSHIP_VERSION_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_LOGS_FIELD;
import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

public record ReplicaInventory(
        String serverId,
        String membershipVersion,
        List<EventReplicaMetadata> events,
        List<TopicLogReplicaMetadata> topicLogs
) {
    public ReplicaInventory {
        serverId = HexCodec.normalizeSha256(serverId, SERVER_ID_FIELD);
        membershipVersion = requireNonBlank(membershipVersion, MEMBERSHIP_VERSION_FIELD);
        events = List.copyOf(requireNonNull(events, EVENTS_FIELD));
        topicLogs = List.copyOf(requireNonNull(topicLogs, TOPIC_LOGS_FIELD));
    }
}
