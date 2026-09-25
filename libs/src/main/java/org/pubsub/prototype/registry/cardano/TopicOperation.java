package org.pubsub.prototype.registry.cardano;

public enum TopicOperation {
    DELETE_TOPIC("deleteTopic", true),
    ADD_OWNER("addOwner", true),
    REMOVE_OWNER("removeOwner", true),
    ADD_ADMIN("addAdmin", true),
    REMOVE_ADMIN("removeAdmin", true),
    ADD_PUBLISHER("addPublisher", false),
    REMOVE_PUBLISHER("removePublisher", false),
    SET_REPLICATION_FACTOR("setReplicationFactor", false),
    SET_RETENTION_PERIOD("setRetentionPeriod", false);

    private final String value;
    private final boolean ownerOnly;

    TopicOperation(String value, boolean ownerOnly) {
        this.value = value;
        this.ownerOnly = ownerOnly;
    }

    public String value() {
        return value;
    }

    public boolean ownerOnly() {
        return ownerOnly;
    }

    boolean hasActorValue() {
        return switch (this) {
            case ADD_OWNER, REMOVE_OWNER, ADD_ADMIN, REMOVE_ADMIN, ADD_PUBLISHER, REMOVE_PUBLISHER -> true;
            default -> false;
        };
    }

    public static TopicOperation fromValue(String value) {
        for (TopicOperation operation : values()) {
            if (operation.value.equals(value)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("Unsupported topic mutation: " + value);
    }
}
