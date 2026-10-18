package org.pubsub.prototype.registry.topic;

import java.util.List;

import static org.pubsub.prototype.util.validation.Validators.require;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;
import static org.pubsub.prototype.util.validation.Validators.requirePositive;
import static org.pubsub.prototype.util.validation.Validators.requireRange;

/**
 * A record object that holds the state of the topic, as it is stored in the registry contract.
 *
 * @param topicId the unique identifier of the topic. It is a mandatory field
 * @param name the name of the topic. It is a mandatory field
 * @param owners the list of owners of the topic. At least one owner is required
 * @param admins the list of admins of the topic
 * @param publishers the list of publishers of the topic
 * @param replicationFactor the replication factor of the topic, from 1 to 50
 * @param retentionPeriod the retention period of the topic in epochs. It must be a positive value
 * @param active indicates whether the topic is active or tombstoned
 */
public record TopicState(
        TopicId topicId,
        String name,
        List<String> owners,
        List<String> admins,
        List<String> publishers,
        int replicationFactor,
        long retentionPeriod,
        boolean active
) {
    public static final int MAX_REPLICATION_FACTOR = 50;

    public TopicState {
        requireNonNull(topicId, RegistryField.TOPIC_ID.jsonName());
        requireNonNull(name, RegistryField.NAME.jsonName());
        owners = List.copyOf(requireNonNull(owners, RegistryField.OWNERS.jsonName()));
        admins = List.copyOf(requireNonNull(admins, RegistryField.ADMINS.jsonName()));
        publishers = List.copyOf(requireNonNull(publishers, RegistryField.PUBLISHERS.jsonName()));
        require(!owners.isEmpty(), "at least one owner is required");
        requireRange(replicationFactor, 1, MAX_REPLICATION_FACTOR, RegistryField.REPLICATION_FACTOR.jsonName());
        requirePositive(retentionPeriod, RegistryField.RETENTION_PERIOD.jsonName());
    }

    public TopicState withOwners(List<String> newOwners) {
        return new TopicState(topicId, name, newOwners, admins, publishers, replicationFactor, retentionPeriod, active);
    }

    public TopicState withAdmins(List<String> newAdmins) {
        return new TopicState(topicId, name, owners, newAdmins, publishers, replicationFactor, retentionPeriod, active);
    }

    public TopicState withPublishers(List<String> newPublishers) {
        return new TopicState(topicId, name, owners, admins, newPublishers, replicationFactor, retentionPeriod, active);
    }

    public TopicState withReplicationFactor(int newReplicationFactor) {
        return new TopicState(topicId, name, owners, admins, publishers, newReplicationFactor, retentionPeriod, active);
    }

    public TopicState withRetentionPeriod(long newRetentionPeriod) {
        return new TopicState(topicId, name, owners, admins, publishers, replicationFactor, newRetentionPeriod, active);
    }

    public TopicState tombstone() {
        return new TopicState(topicId, name, owners, admins, publishers, replicationFactor, retentionPeriod, false);
    }
}
