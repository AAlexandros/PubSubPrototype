package org.pubsub.prototype.registry;

public enum RegistryField {
    TOPIC_ID("topicId"),
    NAME("name"),
    OWNERS("owners"),
    ADMINS("admins"),
    PUBLISHERS("publishers"),
    REPLICATION_FACTOR("replicationFactor"),
    RETENTION_PERIOD("retentionPeriod"),
    ACTIVE("active"),
    TOPICS("topics"),
    OBSERVED_AT("observedAt");

    private final String jsonName;

    RegistryField(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
