package org.pubsub.prototype.persistence;

public record EventReplicaMetadata(
        String eventKey,
        String topicId,
        String publisherKeyId,
        long sequenceNumber,
        long topicRetentionPeriod,
        long storedAtEpoch,
        long expiresAfterEpoch
) {
    public EventReplicaMetadata {
        eventKey = PersistenceHex.require256(eventKey, "eventKey");
        topicId = PersistenceHex.require256(topicId, "topicId");
        publisherKeyId = PersistenceHex.require256(publisherKeyId, "publisherKeyId");
        if (sequenceNumber < 0 || topicRetentionPeriod <= 0 || storedAtEpoch < 0
                || expiresAfterEpoch < storedAtEpoch) {
            throw new IllegalArgumentException("invalid event replica metadata");
        }
    }
}
