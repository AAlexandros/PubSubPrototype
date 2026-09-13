package org.pubsub.prototype.persistence;

public record PublisherProgress(String publisherKeyId, long latestSequenceNumber, long latestTimestamp) {
    public PublisherProgress {
        publisherKeyId = PersistenceHex.require256(publisherKeyId, "publisherKeyId");
        if (latestSequenceNumber < 0 || latestTimestamp < 0) throw new IllegalArgumentException("progress must be non-negative");
    }
}
