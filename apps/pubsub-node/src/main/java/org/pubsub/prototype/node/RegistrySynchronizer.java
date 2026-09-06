package org.pubsub.prototype.node;

import org.pubsub.prototype.registry.RegistrySnapshot;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;
import org.pubsub.prototype.registry.cardano.CardanoTopicRegistry;
import org.pubsub.prototype.event.TopicStateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class RegistrySynchronizer implements TopicStateProvider, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrySynchronizer.class);

    private final CardanoTopicRegistry registry;
    private final long pollIntervalMs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "registry-sync");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Map<TopicId, TopicState> cachedTopics = Map.of();
    private boolean syncedOnce;

    RegistrySynchronizer(CardanoTopicRegistry registry, long pollIntervalMs) {
        this.registry = registry;
        this.pollIntervalMs = pollIntervalMs;
    }

    void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public java.util.Optional<TopicState> topic(TopicId topicId) {
        return java.util.Optional.ofNullable(cachedTopics.get(topicId));
    }

    /** Active (non-tombstoned) topic ids, for deterministic Navigation-layer topic ordering. */
    List<String> activeTopicIds() {
        return cachedTopics.values().stream().filter(TopicState::active).map(topic -> topic.topicId().value()).toList();
    }

    private void pollSafely() {
        try {
            RegistrySnapshot snapshot = registry.snapshotIncludingTombstones();
            Map<TopicId, TopicState> next = byId(snapshot.topics());
            if (!syncedOnce || !next.equals(cachedTopics)) {
                logChanges(cachedTopics, next);
                cachedTopics = next;
                syncedOnce = true;
                LOG.info("REGISTRY_SYNCED topics={}", cachedTopics.size());
            }
        } catch (RuntimeException ex) {
            LOG.warn("REGISTRY_TX_REJECTED reason={}", ex.getMessage());
        }
    }

    private void logChanges(Map<TopicId, TopicState> previous, Map<TopicId, TopicState> next) {
        for (TopicState topic : next.values()) {
            TopicState old = previous.get(topic.topicId());
            if (old == null) {
                LOG.info("TOPIC_CREATED topicId={} name={}", topic.topicId(), topic.name());
            } else if (!old.equals(topic)) {
                LOG.info("TOPIC_UPDATED topicId={} name={}", topic.topicId(), topic.name());
            }
        }
        for (TopicId topicId : previous.keySet()) {
            if (!next.containsKey(topicId)) {
                LOG.info("TOPIC_DELETED topicId={}", topicId);
            }
        }
    }

    private static Map<TopicId, TopicState> byId(List<TopicState> topics) {
        Map<TopicId, TopicState> byId = new HashMap<>();
        for (TopicState topic : topics) {
            byId.put(topic.topicId(), topic);
        }
        return Map.copyOf(byId);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
