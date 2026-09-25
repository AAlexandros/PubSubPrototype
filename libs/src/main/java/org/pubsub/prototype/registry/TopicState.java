package org.pubsub.prototype.registry;

import java.util.List;

import static org.pubsub.prototype.util.Validators.require;
import static org.pubsub.prototype.util.Validators.requireNonNull;
import static org.pubsub.prototype.util.Validators.requirePositive;

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
    public TopicState {
        requireNonNull(topicId, RegistryField.TOPIC_ID.jsonName());
        requireNonNull(name, RegistryField.NAME.jsonName());
        owners = List.copyOf(requireNonNull(owners, RegistryField.OWNERS.jsonName()));
        admins = List.copyOf(requireNonNull(admins, RegistryField.ADMINS.jsonName()));
        publishers = List.copyOf(requireNonNull(publishers, RegistryField.PUBLISHERS.jsonName()));
        require(!owners.isEmpty(), "at least one owner is required");
        requirePositive(replicationFactor, RegistryField.REPLICATION_FACTOR.jsonName());
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
