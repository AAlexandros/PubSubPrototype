package org.pubsub.prototype.persistence;

import org.pubsub.prototype.util.HexUtils;
import java.util.List;

import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_ID_FIELD;

/**
 * Record that keeps the metadata (latest sequence number, and latest publish
 * timestamp) for all publishers of a topic.
 */
public record TopicLogReplicaMetadata(String topicId, List<PublisherProgress> publishers) {

    public TopicLogReplicaMetadata {

        topicId = HexUtils.normalizeSha256(topicId, TOPIC_ID_FIELD);
        publishers = List.copyOf(publishers);
    }

}
