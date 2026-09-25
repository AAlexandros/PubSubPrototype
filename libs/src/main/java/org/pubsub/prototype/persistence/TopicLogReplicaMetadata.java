package org.pubsub.prototype.persistence;

import java.util.List;

import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_ID_FIELD;

public record TopicLogReplicaMetadata(String topicId, List<PublisherProgress> publishers) {
    public TopicLogReplicaMetadata {
        topicId = PersistenceHex.require256(topicId, TOPIC_ID_FIELD);
        publishers = List.copyOf(publishers);
    }
}
