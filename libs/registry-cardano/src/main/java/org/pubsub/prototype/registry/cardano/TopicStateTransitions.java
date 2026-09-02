package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.RegistryAuthorizationException;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.TopicState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TopicStateTransitions {
    private TopicStateTransitions() {
    }

    static TopicState apply(TopicState current, TopicMutation mutation) {
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

    static void authorize(String signer, TopicState topic, TopicMutation mutation) {
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
        Set<String> set = new LinkedHashSet<>(values);
        set.add(value);
        return List.copyOf(set);
    }

    private static List<String> remove(List<String> values, String value) {
        Set<String> set = new LinkedHashSet<>(values);
        set.remove(value);
        return List.copyOf(set);
    }

    private static List<String> removeOwner(List<String> values, String value) {
        List<String> owners = remove(values, value);
        if (owners.isEmpty()) {
            throw new RegistryConflictException("last owner cannot be removed");
        }
        return owners;
    }
}
