package org.pubsub.prototype.http;

/** HTTP paths shared by servers, clients, and route-level tests. */
public final class ApiPaths {
    public static final String PUBLISHERS_SEGMENT = "publishers";
    public static final String EVENTS = "/v1/events";
    public static final String TOPICS = "/v1/topics";
    public static final String REPLICA_EVENTS = "/v1/replicas/events";
    public static final String REPLICA_TOPICS = "/v1/replicas/topics";
    public static final String REPLICA_REPAIR = "/v1/replicas/repair";
    public static final String MAINTENANCE_STATUS = "/v1/maintenance/status";
    public static final String MAINTENANCE_REPLICAS = "/v1/maintenance/replicas";
    public static final String HEALTH = "/v1/health";

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
