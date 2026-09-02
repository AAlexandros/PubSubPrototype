package org.pubsub.prototype.registry;

import java.util.List;
import java.util.Objects;

public record CreateTopicRequest(
        String name,
        List<String> admins,
        List<String> publishers,
        int replicationFactor,
        long retentionPeriod
) {
    public CreateTopicRequest {
        Objects.requireNonNull(name, RegistryField.NAME.jsonName());
        if (name.isBlank()) {
            throw new IllegalArgumentException(RegistryField.NAME.jsonName() + " is required");
        }
        admins = List.copyOf(Objects.requireNonNull(admins, RegistryField.ADMINS.jsonName()));
        publishers = List.copyOf(Objects.requireNonNull(publishers, RegistryField.PUBLISHERS.jsonName()));
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException(RegistryField.REPLICATION_FACTOR.jsonName() + " must be greater than zero");
        }
        if (retentionPeriod <= 0) {
            throw new IllegalArgumentException(RegistryField.RETENTION_PERIOD.jsonName() + " must be greater than zero");
        }
    }
}
