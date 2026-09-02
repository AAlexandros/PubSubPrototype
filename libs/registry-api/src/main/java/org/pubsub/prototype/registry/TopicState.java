package org.pubsub.prototype.registry;

import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(topicId, RegistryField.TOPIC_ID.jsonName());
        Objects.requireNonNull(name, RegistryField.NAME.jsonName());
        owners = List.copyOf(Objects.requireNonNull(owners, RegistryField.OWNERS.jsonName()));
        admins = List.copyOf(Objects.requireNonNull(admins, RegistryField.ADMINS.jsonName()));
        publishers = List.copyOf(Objects.requireNonNull(publishers, RegistryField.PUBLISHERS.jsonName()));
        if (owners.isEmpty()) {
            throw new IllegalArgumentException("at least one owner is required");
        }
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException(RegistryField.REPLICATION_FACTOR.jsonName() + " must be greater than zero");
        }
        if (retentionPeriod <= 0) {
            throw new IllegalArgumentException(RegistryField.RETENTION_PERIOD.jsonName() + " must be greater than zero");
        }
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
