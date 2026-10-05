package org.pubsub.prototype.registry;

import java.util.Optional;

/**
 * Registry of topic metadata: access roles, replication factor, and retention period.
 *
 * <p>Read operations expose the observed registry state. Mutations are performed by the
 * implementation's configured signer: owners may manage owners and delete a topic; owners or
 * admins may manage admins, publishers, replication, and retention. A mutation may throw
 * {@link RegistryAuthorizationException} when the signer lacks permission or
 * {@link RegistryConflictException} when the requested state change is invalid.</p>
 */
public interface TopicRegistry {
    /** Returns the currently active topics visible to the registry. */
    RegistrySnapshot snapshot();

    /** Returns the state for a topic, given its ID. */
    Optional<TopicState> topic(TopicId topicId);

    /** Creates a topic from its initial metadata and returns its stable ID. */
    TopicId createTopic(CreateTopicRequest request);

    /** Marks a topic inactive. */
    void deleteTopic(TopicId topicId);

    /** Grants owner access to an identity. */
    void addOwner(TopicId topicId, String owner);

    /** Revokes owner access; implementations must retain at least one owner. */
    void removeOwner(TopicId topicId, String owner);

    /** Grants administrative access to an identity. */
    void addAdmin(TopicId topicId, String admin);

    /** Revokes administrative access from an identity. */
    void removeAdmin(TopicId topicId, String admin);

    /** Allows an identity to publish events for a topic. */
    void addPublisher(TopicId topicId, String publisher);

    /** Removes an identity from the topic's publisher allow-list. */
    void removePublisher(TopicId topicId, String publisher);

    /** Sets the number of replication servers required for each event. */
    void setReplicationFactor(TopicId topicId, int replicationFactor);

    /** Sets how long events for the topic are retained. */
    void setRetentionPeriod(TopicId topicId, long retentionPeriod);
}
