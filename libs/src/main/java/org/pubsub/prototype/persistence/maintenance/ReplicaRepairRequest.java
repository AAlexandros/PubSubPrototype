package org.pubsub.prototype.persistence.maintenance;

import org.pubsub.prototype.util.HexUtils;

import java.util.List;

import static org.pubsub.prototype.constants.PersistenceConstants.RECORD_KEY_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.RECORD_TYPE_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.UNAVAILABLE_SERVER_IDS_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.UNAVAILABLE_SERVER_ID_FIELD;
import static org.pubsub.prototype.util.validation.Validators.require;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;

/** Identifies a missing replica, possible sources, and peers reported unavailable. */
public record ReplicaRepairRequest(
        ReplicaRecordType recordType,
        String recordKey,
        String topicId,
        List<String> sourceServerIds,
        List<String> unavailableServerIds
) {
    public ReplicaRepairRequest {
        requireNonNull(recordType, RECORD_TYPE_FIELD);
        recordKey = HexUtils.normalizeSha256(recordKey, RECORD_KEY_FIELD);
        topicId = HexUtils.normalizeSha256(topicId, TOPIC_ID_FIELD);
        if (recordType == ReplicaRecordType.TOPIC_LOG && !recordKey.equals(topicId))
            throw new IllegalArgumentException("topic log key must equal topic ID");
        sourceServerIds = requireNonNull(sourceServerIds, SERVER_ID_FIELD).stream()
                .map(value -> HexUtils.normalizeSha256(value, SERVER_ID_FIELD)).toList();
        require(!sourceServerIds.isEmpty(), "sourceServerIds must not be empty");
        unavailableServerIds = requireNonNull(unavailableServerIds, UNAVAILABLE_SERVER_IDS_FIELD).stream()
                .map(value -> HexUtils.normalizeSha256(value, UNAVAILABLE_SERVER_ID_FIELD)).toList();
    }
}
