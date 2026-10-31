package org.pubsub.prototype.registry;

import org.pubsub.prototype.registry.topic.TopicState;

import java.util.List;
import java.util.Map;

/**
 * Builds a common registry status response for the Pub/Sub node and replication server.
 * The response helps operators inspect what each application last observed.
 */
public final class RegistryDiagnostics {
    private static final String TOPIC = "topic";
    private static final String TOPIC_AGE_MS = "topicAgeMs";
    private static final String TOPICS = "topics";
    private static final String REPLICATION = "replication";
    private static final String REPLICATION_AGE_MS = "replicationAgeMs";
    private static final String MEMBERS = "members";

    private RegistryDiagnostics() {
    }

    /**
     * Returns each registry's freshness, observation age, and last observed entries.
     * The entries remain visible for diagnosis when an observation becomes stale.
     */
    public static Map<String, Object> snapshot(RegistryObserver.Freshness topicFreshness,
                                                long topicAgeMs, List<TopicState> topics,
                                                RegistryObserver.Freshness replicationFreshness,
                                                long replicationAgeMs, List<?> members) {
        return Map.of(TOPIC, topicFreshness.name(), TOPIC_AGE_MS, topicAgeMs, TOPICS, topics,
                REPLICATION, replicationFreshness.name(), REPLICATION_AGE_MS, replicationAgeMs,
                MEMBERS, members);
    }
}
