package org.pubsub.prototype.persistence;

import java.util.List;

public record ReplicaRepairStatus(
        ReplicaRecordType recordType,
        String recordKey,
        String topicId,
        String membershipVersion,
        List<String> sourceServerIds,
        int attempts,
        String lastError
) {
    public ReplicaRepairStatus {
        sourceServerIds = List.copyOf(sourceServerIds);
    }
}
