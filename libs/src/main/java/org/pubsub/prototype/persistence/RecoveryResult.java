package org.pubsub.prototype.persistence;

import java.util.List;

import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_ID_FIELD;

public record RecoveryResult(String topicId, int missingCount, int deliveredCount,
                             List<String> deliveredEventKeys, List<String> unavailableEventKeys) {
    public RecoveryResult {
        topicId = PersistenceHex.require256(topicId, TOPIC_ID_FIELD);
        deliveredEventKeys = List.copyOf(deliveredEventKeys);
        unavailableEventKeys = List.copyOf(unavailableEventKeys);
    }
}
