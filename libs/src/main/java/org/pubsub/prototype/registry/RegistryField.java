package org.pubsub.prototype.registry;

/** Field names used by registry data structures. */
public enum RegistryField {
    /** Unique topic identifier. */
    TOPIC_ID("topicId"),

    /** Human-readable topic name. */
    NAME("name"),

    /** Identities authorized to manage the topic. */
    OWNERS("owners"),

    /** Identities authorized to administer the topic. */
    ADMINS("admins"),

    /** Identities authorized to publish to the topic. */
    PUBLISHERS("publishers"),

    /** Number of replicas required for each event. */
    REPLICATION_FACTOR("replicationFactor"),

    /** Duration for which topic events are retained. */
    RETENTION_PERIOD("retentionPeriod"),

    /** Whether the topic is active. */
    ACTIVE("active"),

    /** Topics contained in a registry snapshot. */
    TOPICS("topics"),

    /** Time at which a registry snapshot was observed. */
    OBSERVED_AT("observedAt");

    private final String jsonName;

    RegistryField(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
