package org.pubsub.prototype.node.service;

import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.node.util.NodeLog;
import org.pubsub.prototype.registry.topic.RegistrySnapshot;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.topic.TopicRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the Cardano Topic Registry and caches topic states, including inactive
 * topics. Event validation reads the cache; Navigation and Dissemination use
 * its active-topic list to update their routing state.
 */
public final class TopicRegistrySyncService implements TopicStateProvider, AutoCloseable {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(TopicRegistrySyncService.class);

    private final TopicRegistry registry;
    private final long pollIntervalMs;
    private final ScheduledExecutorService executor =
            AsyncUtil.scheduledSingleThread("registry-sync");
    private volatile Map<TopicId, TopicState> cachedTopics = Map.of();
    private boolean syncedOnce;

    public TopicRegistrySyncService(TopicRegistry registry, long pollIntervalMs) {
        this.registry = registry;
        this.pollIntervalMs = pollIntervalMs;
        NodeLog.init(LOG, "REGISTRY_CONFIGURED pollIntervalMs={}", pollIntervalMs);
    }

    public void start() {
        NodeLog.init(LOG, "REGISTRY_POLLING_STARTED intervalMs={}", pollIntervalMs);
        executor.scheduleWithFixedDelay(this::pollSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public java.util.Optional<TopicState> findTopicState(TopicId topicId) {
        var result = java.util.Optional.ofNullable(cachedTopics.get(topicId));
        LOG.info("TOPIC_LOOKUP topicId={} found={} active={}", topicId, result.isPresent(),
                result.map(TopicState::active).orElse(false));
        return result;
    }

    /** Active (non-tombstoned) topic IDs used to build the deterministic Navigation topic index. */
    public List<String> activeTopicIds() {
        List<String> result = cachedTopics.values().stream().filter(TopicState::active)
                .map(topic -> topic.topicId().value()).toList();
        LOG.info("ACTIVE_TOPICS_OUT topicIds={}", result);
        return result;
    }

    private void pollSafely() {
        try {
            RegistrySnapshot allTopicsSnapshot = registry.allTopicsSnapshot();
            Map<TopicId, TopicState> next = byId(allTopicsSnapshot.topics());
            if (!syncedOnce || !next.equals(cachedTopics)) {
                boolean firstSync = !syncedOnce;
                logChanges(cachedTopics, next);
                cachedTopics = next;
                syncedOnce = true;
                long activeCount = cachedTopics.values().stream().filter(TopicState::active).count();
                if (firstSync) {
                    NodeLog.init(LOG, "REGISTRY_READY topics={} active={}", cachedTopics.size(), activeCount);
                } else {
                    LOG.info("REGISTRY_SYNCED topics={} active={}", cachedTopics.size(), activeCount);
                }
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
