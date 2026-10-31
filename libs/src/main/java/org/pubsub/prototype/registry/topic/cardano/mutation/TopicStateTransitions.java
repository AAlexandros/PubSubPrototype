package org.pubsub.prototype.registry.topic.cardano.mutation;

import org.pubsub.prototype.registry.RegistryAuthorizationException;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.topic.TopicState;

import java.util.ArrayList;
import java.util.List;

public final class TopicStateTransitions {
    private TopicStateTransitions() {
    }

    public static TopicState apply(TopicState current, TopicMutation mutation) {
        if (!current.active() && mutation.operation() != TopicOperation.DELETE_TOPIC) {
            throw new RegistryConflictException("inactive topic cannot be modified");
        }
        return switch (mutation.operation()) {
            case DELETE_TOPIC -> current.tombstone();
            case ADD_OWNER -> current.withOwners(add(current.owners(), mutation.value()));
            case REMOVE_OWNER -> current.withOwners(removeOwner(current.owners(), mutation.value()));
            case ADD_ADMIN -> current.withAdmins(add(current.admins(), mutation.value()));
            case REMOVE_ADMIN -> current.withAdmins(remove(current.admins(), mutation.value()));
            case ADD_PUBLISHER -> current.withPublishers(add(current.publishers(), mutation.value()));
            case REMOVE_PUBLISHER -> current.withPublishers(remove(current.publishers(), mutation.value()));
            case SET_REPLICATION_FACTOR -> current.withReplicationFactor(Integer.parseInt(mutation.value()));
            case SET_RETENTION_PERIOD -> current.withRetentionPeriod(Long.parseLong(mutation.value()));
        };
    }

    public static void authorize(String signer, TopicState topic, TopicMutation mutation) {
        boolean owner = topic.owners().contains(signer);
        boolean admin = topic.admins().contains(signer);
        if (mutation.operation().ownerOnly() && !owner) {
            throw new RegistryAuthorizationException("owner signature required");
        }
        if (!mutation.operation().ownerOnly() && !owner && !admin) {
            throw new RegistryAuthorizationException("owner or admin signature required");
        }
    }

    private static List<String> add(List<String> values, String value) {
        if (values.contains(value)) return List.copyOf(values);
        List<String> updated = new ArrayList<>(values.size() + 1);
        updated.add(value);
        updated.addAll(values);
        return List.copyOf(updated);
    }

    private static List<String> remove(List<String> values, String value) {
        return values.stream().filter(existing -> !existing.equals(value)).toList();
    }

    private static List<String> removeOwner(List<String> values, String value) {
        List<String> owners = remove(values, value);
        if (owners.isEmpty()) {
            throw new RegistryConflictException("last owner cannot be removed");
        }
        return owners;
    }
}
