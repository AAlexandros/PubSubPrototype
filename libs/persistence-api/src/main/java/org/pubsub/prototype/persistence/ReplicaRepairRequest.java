package org.pubsub.prototype.persistence;

import java.util.List;

public record ReplicaRepairRequest(
        ReplicaRecordType recordType,
        String recordKey,
        String topicId,
        String membershipVersion,
        List<ReplicationServer> survivingReplicas,
        List<String> responsibleServerIds,
        List<String> unavailableServerIds,
        String reason
) {
    public ReplicaRepairRequest {
        if (recordType == null) throw new IllegalArgumentException("recordType is required");
        recordKey = PersistenceHex.require256(recordKey, "recordKey");
        topicId = PersistenceHex.require256(topicId, "topicId");
        if (membershipVersion == null || membershipVersion.isBlank()) {
            throw new IllegalArgumentException("membershipVersion is required");
        }
        survivingReplicas = List.copyOf(survivingReplicas);
        responsibleServerIds = responsibleServerIds.stream()
                .map(value -> PersistenceHex.require256(value, "responsibleServerId")).toList();
        unavailableServerIds = unavailableServerIds.stream()
                .map(value -> PersistenceHex.require256(value, "unavailableServerId")).toList();
        reason = reason == null ? "maintenance" : reason;
    }
}
