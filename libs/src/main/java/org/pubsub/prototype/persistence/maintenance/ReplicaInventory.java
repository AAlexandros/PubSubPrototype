package org.pubsub.prototype.persistence.maintenance;

import org.pubsub.prototype.util.HexUtils;

import java.util.List;

import static org.pubsub.prototype.constants.PersistenceConstants.EVENTS_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.MEMBERSHIP_VERSION_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.TOPIC_LOGS_FIELD;
import static org.pubsub.prototype.util.validation.Validators.requireNonBlank;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;

public record ReplicaInventory(
        String serverId,
        String membershipVersion,
        List<EventReplicaMetadata> events,
        List<TopicLogReplicaMetadata> topicLogs
) {
    public ReplicaInventory {
        serverId = HexUtils.normalizeSha256(serverId, SERVER_ID_FIELD);
        membershipVersion = requireNonBlank(membershipVersion, MEMBERSHIP_VERSION_FIELD);
        events = List.copyOf(requireNonNull(events, EVENTS_FIELD));
        topicLogs = List.copyOf(requireNonNull(topicLogs, TOPIC_LOGS_FIELD));
    }
}
