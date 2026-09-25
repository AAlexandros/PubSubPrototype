package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicRegistry;

public record TopicMutation(TopicOperation operation, String value) {
    public TopicMutation(String operation, String value) {
        this(TopicOperation.fromValue(operation), value);
    }

    public static TopicMutation delete() {
        return new TopicMutation(TopicOperation.DELETE_TOPIC, "");
    }

    public static TopicMutation addOwner(String owner) {
        return new TopicMutation(TopicOperation.ADD_OWNER, owner);
    }

    public static TopicMutation removeOwner(String owner) {
        return new TopicMutation(TopicOperation.REMOVE_OWNER, owner);
    }

    public static TopicMutation addAdmin(String admin) {
        return new TopicMutation(TopicOperation.ADD_ADMIN, admin);
    }

    public static TopicMutation removeAdmin(String admin) {
        return new TopicMutation(TopicOperation.REMOVE_ADMIN, admin);
    }

    public static TopicMutation addPublisher(String publisher) {
        return new TopicMutation(TopicOperation.ADD_PUBLISHER, publisher);
    }

    public static TopicMutation removePublisher(String publisher) {
        return new TopicMutation(TopicOperation.REMOVE_PUBLISHER, publisher);
    }

    public static TopicMutation setReplicationFactor(int replicationFactor) {
        return new TopicMutation(TopicOperation.SET_REPLICATION_FACTOR, Integer.toString(replicationFactor));
    }

    public static TopicMutation setRetentionPeriod(long retentionPeriod) {
        return new TopicMutation(TopicOperation.SET_RETENTION_PERIOD, Long.toString(retentionPeriod));
    }

    public void applyTo(TopicRegistry registry, TopicId topicId) {
        switch (operation) {
            case DELETE_TOPIC -> registry.deleteTopic(topicId);
            case ADD_OWNER -> registry.addOwner(topicId, value);
            case REMOVE_OWNER -> registry.removeOwner(topicId, value);
            case ADD_ADMIN -> registry.addAdmin(topicId, value);
            case REMOVE_ADMIN -> registry.removeAdmin(topicId, value);
            case ADD_PUBLISHER -> registry.addPublisher(topicId, value);
            case REMOVE_PUBLISHER -> registry.removePublisher(topicId, value);
            case SET_REPLICATION_FACTOR -> registry.setReplicationFactor(topicId, Integer.parseInt(value));
            case SET_RETENTION_PERIOD -> registry.setRetentionPeriod(topicId, Long.parseLong(value));
        }
    }

    String operationValue() {
        return operation.value();
    }
}
