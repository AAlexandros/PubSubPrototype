package org.pubsub.prototype.persistence;

import java.util.List;

public record RecoveryResult(String topicId, int missingCount, int deliveredCount,
                             List<String> deliveredEventKeys, List<String> unavailableEventKeys) {
    public RecoveryResult {
        topicId = PersistenceHex.require256(topicId, "topicId");
        deliveredEventKeys = List.copyOf(deliveredEventKeys);
        unavailableEventKeys = List.copyOf(unavailableEventKeys);
    }
}
