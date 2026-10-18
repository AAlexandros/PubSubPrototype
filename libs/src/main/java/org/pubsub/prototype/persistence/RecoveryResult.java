package org.pubsub.prototype.persistence;

import org.pubsub.prototype.util.HexUtils;

import java.util.List;

import static org.pubsub.prototype.constants.PersistenceConstants.TOPIC_ID_FIELD;

public record RecoveryResult(String topicId, int missingCount, int deliveredCount,
                             List<String> deliveredEventKeys, List<String> unavailableEventKeys) {
    public RecoveryResult {
        topicId = HexUtils.normalizeSha256(topicId, TOPIC_ID_FIELD);
        deliveredEventKeys = List.copyOf(deliveredEventKeys);
        unavailableEventKeys = List.copyOf(unavailableEventKeys);
    }
}
