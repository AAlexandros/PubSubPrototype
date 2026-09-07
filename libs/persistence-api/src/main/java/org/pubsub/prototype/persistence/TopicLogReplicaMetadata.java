package org.pubsub.prototype.persistence;

import java.util.List;

public record TopicLogReplicaMetadata(String topicId, List<PublisherProgress> publishers) {
    public TopicLogReplicaMetadata {
        topicId = PersistenceHex.require256(topicId, "topicId");
        publishers = List.copyOf(publishers);
    }
}
