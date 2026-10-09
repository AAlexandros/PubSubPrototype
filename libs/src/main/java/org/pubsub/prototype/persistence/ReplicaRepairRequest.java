package org.pubsub.prototype.persistence;

import org.pubsub.prototype.util.HexCodec;

import java.util.List;

import static org.pubsub.prototype.util.PersistenceConstants.MEMBERSHIP_VERSION_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.MAINTENANCE_REASON;
import static org.pubsub.prototype.util.PersistenceConstants.RECORD_KEY_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.RECORD_TYPE_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.RESPONSIBLE_SERVER_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.RESPONSIBLE_SERVER_IDS_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SURVIVING_REPLICAS_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.UNAVAILABLE_SERVER_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.UNAVAILABLE_SERVER_IDS_FIELD;
import static org.pubsub.prototype.util.Validators.requireNonBlank;
import static org.pubsub.prototype.util.Validators.requireNonNull;

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
        requireNonNull(recordType, RECORD_TYPE_FIELD);
        recordKey = HexCodec.normalizeSha256(recordKey, RECORD_KEY_FIELD);
        topicId = HexCodec.normalizeSha256(topicId, TOPIC_ID_FIELD);
        membershipVersion = requireNonBlank(membershipVersion, MEMBERSHIP_VERSION_FIELD);
        survivingReplicas = List.copyOf(requireNonNull(survivingReplicas, SURVIVING_REPLICAS_FIELD));
        responsibleServerIds = requireNonNull(responsibleServerIds, RESPONSIBLE_SERVER_IDS_FIELD).stream()
                .map(value -> HexCodec.normalizeSha256(value, RESPONSIBLE_SERVER_ID_FIELD)).toList();
        unavailableServerIds = requireNonNull(unavailableServerIds, UNAVAILABLE_SERVER_IDS_FIELD).stream()
                .map(value -> HexCodec.normalizeSha256(value, UNAVAILABLE_SERVER_ID_FIELD)).toList();
        reason = reason == null ? MAINTENANCE_REASON : reason;
    }
}
