package org.pubsub.prototype.registry;

import java.util.Optional;

public interface TopicRegistry {
    RegistrySnapshot snapshot();

    Optional<TopicState> topic(TopicId topicId);

    TopicId createTopic(CreateTopicRequest request);

    void deleteTopic(TopicId topicId);

    void addOwner(TopicId topicId, String owner);

    void removeOwner(TopicId topicId, String owner);

    void addAdmin(TopicId topicId, String admin);

    void removeAdmin(TopicId topicId, String admin);

    void addPublisher(TopicId topicId, String publisher);

    void removePublisher(TopicId topicId, String publisher);

    void setReplicationFactor(TopicId topicId, int replicationFactor);

    void setRetentionPeriod(TopicId topicId, long retentionPeriod);
}
