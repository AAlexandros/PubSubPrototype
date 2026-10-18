package org.pubsub.prototype.registry.topic;

import java.util.List;

import static org.pubsub.prototype.util.validation.Validators.requireNonBlank;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;
import static org.pubsub.prototype.util.validation.Validators.requirePositive;
import static org.pubsub.prototype.util.validation.Validators.requireRange;

/**
 * Initial metadata required to create a topic in the registry.
 * It is used to validate and pass the data around.
 */
public record CreateTopicRequest(
        String name,
        List<String> admins,
        List<String> publishers,
        int replicationFactor,
        long retentionPeriod
) {
    public CreateTopicRequest {
        name = requireNonBlank(name, RegistryField.NAME.jsonName());
        admins = List.copyOf(requireNonNull(admins, RegistryField.ADMINS.jsonName()));
        publishers = List.copyOf(requireNonNull(publishers, RegistryField.PUBLISHERS.jsonName()));
        requireRange(replicationFactor, 1, TopicState.MAX_REPLICATION_FACTOR,
                RegistryField.REPLICATION_FACTOR.jsonName());
        requirePositive(retentionPeriod, RegistryField.RETENTION_PERIOD.jsonName());
    }
}
