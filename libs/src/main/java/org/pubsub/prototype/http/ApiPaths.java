package org.pubsub.prototype.http;

/** HTTP paths shared by servers, clients, and route-level tests. */
public final class ApiPaths {
    public static final String PUBLISHERS_SEGMENT = "publishers";
    public static final String CLIENT_ROOT = "/v1/client";
    public static final String REPLICATION_ROOT = "/v1/replication";
    public static final String OPS_ROOT = "/v1/ops";
    public static final String CLIENT_EVENTS = CLIENT_ROOT + "/events";
    public static final String CLIENT_TOPICS = CLIENT_ROOT + "/topics";
    public static final String REPLICATION_EVENTS = REPLICATION_ROOT + "/events";
    public static final String REPLICATION_TOPICS = REPLICATION_ROOT + "/topics";
    public static final String REPLICATION_REPAIR = REPLICATION_ROOT + "/repair";
    public static final String REPLICATION_INVENTORY = REPLICATION_ROOT + "/inventory";
    public static final String OPS_MAINTENANCE_STATUS = OPS_ROOT + "/maintenance/status";
    public static final String OPS_HEALTH = OPS_ROOT + "/health";
    public static final String OPS_REGISTRY = OPS_ROOT + "/registry";

    public static final String EVENT_PUBLISH = "/v1/events/publish";
    public static final String EVENT_INJECT = "/v1/events/inject";
    public static final String PUBLISHER_KEY_ID = "/v1/events/publisher-key-id";
    public static final String EVENT_RECOVER = "/v1/events/recover";
    public static final String EVENT_RECOVERY_STATE = "/v1/events/recovery-state";
    public static final String PEER_SAMPLING_VIEW = "/v1/peer-sampling/view";
    public static final String NAVIGATION_VIEW = "/v1/navigation/view";
    public static final String DISSEMINATION_VIEW = "/v1/dissemination/view";
    public static final String SUBSCRIPTIONS = "/v1/subscriptions";

    public static String child(String base, String identifier) {
        return base + "/" + identifier;
    }

    public static String publishers(String base, String topicId) {
        return child(base, topicId) + "/" + PUBLISHERS_SEGMENT;
    }

    private ApiPaths() {
    }
}
