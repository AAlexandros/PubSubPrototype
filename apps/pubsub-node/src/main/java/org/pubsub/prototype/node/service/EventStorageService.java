package org.pubsub.prototype.node.service;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.node.util.NodeLog;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.RecoveryResult;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.DeliveryStateStore;
import org.pubsub.prototype.persistence.adapter.DeliveryStateStore.DeliveryProgress;
import org.pubsub.prototype.persistence.adapter.NodeEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.persistence.core.ReplicationServerClient;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.pubsub.prototype.constants.PersistenceConstants.CONCURRENCY_FIELD;
import static org.pubsub.prototype.util.validation.Validators.requirePositive;

/**
 * Handles event storage and recovery for a Pub/Sub node. Accepted events created
 * and published through this node are queued for storage on replication servers.
 * 
 * Subscriber events are persisted locally before advancing delivery progress.
 */
public final class EventStorageService implements AutoCloseable {
    
    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(EventStorageService.class);
    
    /** Keeps replication-server storage requests off the publishing thread and sends queued events in order. */
    private final ExecutorService storageExecutor;
    /** Runs recovery lookups concurrently; recovered events are still delivered in publisher sequence order. */
    private final ExecutorService recoveryFetchers;

    private final ReplicationServerClient replicationServerClient;
    private final DeliveryStateStore deliveryState;
    private final NodeEventStore localEvents;
    private final PersistenceEventValidator recoveryValidator;
    private final TopicStateProvider topics;
    private final ReplicationMembership membership;
    private final String nodeId;
    private final ScheduledExecutorService catchUpExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "event-catch-up");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean catchUpStarted;
    private volatile boolean initialCatchUpCompleted;
    private final Map<String, CatchUpTopicStatus> catchUpTopics = new LinkedHashMap<>();

    /**
     * Uses the current replication-registry view to choose active server endpoints for storage and recovery.
     * Each active registration supplies the host and port used for HTTP requests.
     */
    public EventStorageService(NodeConfig.PersistenceSection config, TopicStateProvider topics,
                               ReplicationMembership membership) {
        this(config, topics, membership, "unknown");
    }

    public EventStorageService(NodeConfig.PersistenceSection config, TopicStateProvider topics,
                               ReplicationMembership membership, String nodeId) {
        Path deliveryStatePath = Path.of(config.deliveryStatePath);
        Duration connectionTimeout = Duration.ofMillis(config.connectionTimeoutMs);
        Duration requestTimeout = Duration.ofMillis(config.requestTimeoutMs);

        this.deliveryState = new DeliveryStateStore(deliveryStatePath);
        this.localEvents = new NodeEventStore(deliveryStatePath.toAbsolutePath().getParent().resolve("events"));
        this.deliveryState.reconcile(localEvents);
        this.topics = topics;
        this.membership = membership;
        this.nodeId = nodeId;
        ReplicationHttpClient replicationHttpClient = new ReplicationHttpClient(connectionTimeout, requestTimeout, config.retries);
        this.replicationServerClient = new ReplicationServerClient(membership, replicationHttpClient);
        requirePositive(config.recoveryConcurrency, CONCURRENCY_FIELD);
        this.recoveryValidator = new PersistenceEventValidator(topics);

        this.recoveryFetchers = Executors.newFixedThreadPool(config.recoveryConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "event-recovery-fetch");
            thread.setDaemon(true);
            return thread;
        });
        this.storageExecutor = AsyncUtil.singleThread("event-storage");

        NodeLog.init(LOG, "PERSISTENCE_READY deliveryState={} connectTimeout={} requestTimeout={} retries={} recoveryConcurrency={}",
                deliveryStatePath, connectionTimeout,
                requestTimeout, config.retries, config.recoveryConcurrency);
        LOG.info("LOCAL_EVENT_STORE_LOADED nodeId={} count={} path={}", nodeId, localEvents.size(),
                deliveryStatePath.toAbsolutePath().getParent().resolve("events"));
    }

    public synchronized Map<String, Object> catchUpStatus() {
        return Map.of("initialStarted", catchUpStarted, "initialCompleted", initialCatchUpCompleted,
                "topics", new LinkedHashMap<>(catchUpTopics));
    }

    /** Starts background catch-up for subscriptions loaded from disk at startup. */
    public void startAutomaticCatchUp(Set<String> restoredSubscriptions, Consumer<EventEnvelope> validator) {
        Set<String> subscriptions = Set.copyOf(restoredSubscriptions);
        catchUpExecutor.scheduleWithFixedDelay(() -> {
            if (!catchUpStarted) {
                catchUpStarted = true;
                LOG.info("CATCH_UP_STARTED nodeId={} topics={}", nodeId, subscriptions.size());
            }
            boolean allReady = true;
            for (String topic : subscriptions) {
                try {
                    var topicState = topics.findTopicState(new org.pubsub.prototype.registry.topic.TopicId(topic));
                    if (topicState.isEmpty() || !topicState.orElseThrow().active()) {
                        synchronized (this) {
                            catchUpTopics.put(topic, new CatchUpTopicStatus("INACTIVE", 0, 0, 0, 0, 0,
                                    Map.of(), System.currentTimeMillis(), System.currentTimeMillis()));
                        }
                        continue;
                    }
                    if (membership.activeServers().isEmpty()) {
                        allReady = false;
                        continue;
                    }
                    recover(topic, validator);
                } catch (RuntimeException ex) {
                    allReady = false;
                    LOG.warn("CATCH_UP_FAILED nodeId={} topicId={} reason={}", nodeId, topic, ex.getMessage(), ex);
                    synchronized (this) {
                        catchUpTopics.put(topic, new CatchUpTopicStatus("FAILED", 0, 0, 0, 0, 1,
                                Map.of(), System.currentTimeMillis(), 0));
                    }
                }
            }
            if (allReady && !initialCatchUpCompleted) {
                initialCatchUpCompleted = true;
                LOG.info("CATCH_UP_COMPLETED nodeId={} topics={}", nodeId, subscriptions.size());
            }
        }, 1, 30, TimeUnit.SECONDS);
    }

        /**
     * Fetches events newer than the locally saved sequences. Calls the supplied callback for
     * each validated event, then saves its sequence after the callback returns. The callback is
     * used only for this recovery request.
     */
    public RecoveryResult recover(String topicId, Consumer<EventEnvelope> recoveredEventSink) {
        LOG.info("RECOVER_IN topicId={}", topicId);
        LOG.info("CATCH_UP_TOPIC_STARTED nodeId={} topicId={}", nodeId, topicId);
        long started = System.currentTimeMillis();
        synchronized (this) {
            catchUpTopics.put(topicId, new CatchUpTopicStatus("RUNNING", 0, 0, 0, 0, 0,
                    Map.of(), started, 0));
        }
        List<Missing> missing = new ArrayList<>();
        // A topic-wide timestamp can hide a lagging publisher when another
        // publisher has a newer event. Query all publishers and compare cursors.
        for (PublisherProgress remote :
                replicationServerClient.fetchLatestSequencesByPublisher(topicId, 0)) {
            DeliveryProgress local = deliveryState.progress(topicId, remote.publisherKeyId());
            for (long sequence = local.lastDeliveredSequenceNumber() + 1;
                 sequence <= remote.latestSequenceNumber(); sequence++) {
                missing.add(new Missing(remote.publisherKeyId(), sequence,
                        EventKeys.eventKey(topicId, remote.publisherKeyId(), sequence)));
                if (sequence == Long.MAX_VALUE) break;
            }
        }

        Map<String, List<Missing>> publishers = new LinkedHashMap<>();
        missing.forEach(item -> publishers.computeIfAbsent(item.publisherKeyId, ignored -> new ArrayList<>()).add(item));
        List<String> delivered = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        Map<String, Long> firstGaps = new LinkedHashMap<>();
        int fetched = 0;
        int duplicates = 0;
        int failures = 0;

        for (List<Missing> publisherMissing : publishers.values()) {
            publisherMissing.sort(Comparator.comparingLong(Missing::sequence));
            boolean gap = false;
            // Fetch in bounded windows. Results are applied in publisher order.
            int windowSize = Math.max(1, ((java.util.concurrent.ThreadPoolExecutor) recoveryFetchers).getMaximumPoolSize());
            for (int start = 0; start < publisherMissing.size(); start += windowSize) {
                List<Missing> window = publisherMissing.subList(start, Math.min(start + windowSize, publisherMissing.size()));
                List<CompletableFuture<Optional<StoredEvent>>> fetches = window.stream().map(item ->
                        CompletableFuture.supplyAsync(() -> replicationServerClient.findStoredEvent(topicId, item.eventKey),
                                recoveryFetchers)).toList();
                for (int index = 0; index < window.size(); index++) {
                    Missing item = window.get(index);
                    try {
                        if (gap) { unavailable.add(item.eventKey); continue; }
                        Optional<EventEnvelope> local = localEvents.get(topicId, item.publisherKeyId, item.sequence);
                        if (local.isPresent()) {
                            advanceContiguous(local.orElseThrow());
                            duplicates++;
                            continue;
                        }
                        Optional<StoredEvent> found = fetches.get(index).join();
                        if (found.isEmpty()) {
                            gap = true;
                            unavailable.add(item.eventKey);
                            firstGaps.putIfAbsent(item.publisherKeyId, item.sequence);
                            LOG.warn("CATCH_UP_GAP nodeId={} topicId={} publisherKeyId={} sequenceNumber={} reason=unavailable",
                                    nodeId, topicId, item.publisherKeyId, item.sequence);
                            continue;
                        }
                        StoredEvent record = found.orElseThrow();
                        fetched++;
                        EventEnvelope event = record.eventEnvelope();
                        if (!item.eventKey.equals(record.eventKey()) || event.sequenceNumber() != item.sequence
                                || !topicId.equals(event.topicId())
                                || !item.publisherKeyId.equals(EventCrypto.publisherKeyId(event)))
                            throw new IllegalArgumentException("Recovered event key mismatch");
                        recoveryValidator.validate(event);
                        LOG.info("CATCH_UP_EVENT_FETCHED nodeId={} eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                                nodeId, event.eventId(), topicId, item.publisherKeyId, item.sequence);
                        recoveredEventSink.accept(event);
                        boolean stored = storeLocalSubscriberEvent(event, "CATCH_UP");
                        if (stored) delivered.add(item.eventKey);
                        else duplicates++;
                        LOG.info("CATCH_UP_EVENT_STORED nodeId={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} duplicate={}",
                                nodeId, event.eventId(), topicId, item.publisherKeyId, item.sequence, !stored);
                    } catch (RuntimeException ex) {
                        gap = true;
                        failures++;
                        unavailable.add(item.eventKey);
                        firstGaps.putIfAbsent(item.publisherKeyId, item.sequence);
                        LOG.warn("CATCH_UP_GAP nodeId={} topicId={} publisherKeyId={} sequenceNumber={} reason={}",
                                nodeId, topicId, item.publisherKeyId, item.sequence, ex.getMessage(), ex);
                    }
                }
            }
        }
        long completed = System.currentTimeMillis();
        synchronized (this) {
            catchUpTopics.put(topicId, new CatchUpTopicStatus(firstGaps.isEmpty() ? "COMPLETED" : "GAP",
                    fetched, delivered.size(), duplicates, unavailable.size(), failures, firstGaps, started, completed));
        }
        LOG.info("CATCH_UP_TOPIC_COMPLETED nodeId={} topicId={} fetched={} stored={} duplicate={} unavailable={} failures={} durationMs={}",
                nodeId, topicId, fetched, delivered.size(), duplicates, unavailable.size(), failures, completed - started);
        RecoveryResult result = new RecoveryResult(topicId, missing.size(), delivered.size(), delivered, unavailable);
        LOG.info("RECOVER_OUT topicId={} result={}", topicId, result);
        return result;
    }

    /** Queues an HTTP request to a replication server to store the full event. Returns before the request finishes. */
    public void queueEventForRemoteStorage(EventEnvelope event) {
        LOG.info("PERSIST_QUEUED eventId={} topic={} sequenceNumber={}",
                event.eventId(), NodeLog.topic(event.topicId()), event.sequenceNumber());
        storageExecutor.execute(() -> {
            try {
                NodeLog.network(LOG, "PERSIST_REQUEST eventId={} topic={} sequenceNumber={}",
                        event.eventId(), NodeLog.topic(event.topicId()), event.sequenceNumber());
                replicationServerClient.submitEventForStorage(event);
                NodeLog.network(LOG, "PERSIST_RESULT eventId={} result=stored", event.eventId());
            } catch (RuntimeException ex) {
                NodeLog.network(LOG, "PERSIST_RESULT eventId={} result=failed reason={}", event.eventId(), ex.getMessage());
                LOG.warn("EVENT_PERSIST_FAILED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} reason={}", org.pubsub.prototype.persistence.core.EventKeys.eventKey(event), event.eventId(), event.topicId(), EventCrypto.publisherKeyId(event), event.sequenceNumber(), ex.getMessage());
            }
        });
    }

    /**
     * Writes this node's recovery progress to its local state file. For the event's topic and
     * publisher, it saves the sequence number and timestamp only if the sequence is higher than
     * the saved one. It does not store the event payload or contact a replication server.
     * 
     * Call this only after the event passes validation. Earlier missing events are not tracked.
     */
    public synchronized boolean storeLocalSubscriberEvent(EventEnvelope event, String source) {
        String publisherKeyId = EventCrypto.publisherKeyId(event);
        try {
            boolean stored = localEvents.store(event);
            LOG.info("{} nodeId={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} source={}",
                    stored ? "LOCAL_EVENT_STORED" : "LOCAL_EVENT_STORE_DUPLICATE",
                    nodeId, event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(), source);
            advanceContiguous(event);
            return stored;
        } catch (RuntimeException ex) {
            LOG.error("LOCAL_EVENT_STORE_FAILED nodeId={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} source={} reason={}",
                    nodeId, event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber(), source, ex.getMessage(), ex);
            throw ex;
        }
    }

    private void advanceContiguous(EventEnvelope event) {
        String publisher = EventCrypto.publisherKeyId(event);
        long next = deliveryState.progress(event.topicId(), publisher).lastDeliveredSequenceNumber() + 1;
        while (true) {
            Optional<EventEnvelope> contiguous = localEvents.get(event.topicId(), publisher, next);
            if (contiguous.isEmpty()) break;
            EventEnvelope present = contiguous.orElseThrow();
            deliveryState.recordLatestAcceptedSequence(event.topicId(), publisher, next, present.timestamp());
            next++;
        }
    }

    public Optional<EventEnvelope> localEvent(String eventId) { return localEvents.getById(eventId); }
    public Optional<EventEnvelope> localEvent(String topicId, String publisher, long sequence) {
        return localEvents.get(topicId, publisher, sequence);
    }
    public NodeEventStore.Page localEvents(String topicId, String publisher, int offset, int limit) {
        return localEvents.list(topicId, publisher, offset, limit);
    }

    /** Returns the locally saved sequence and timestamp for each topic and publisher. */
    public Object deliveryState() {
        Object snapshot = deliveryState.snapshot();
        LOG.info("DELIVERY_STATE_READ result={}", snapshot);
        return snapshot;
    }

    @Override
    public void close() {
        catchUpExecutor.shutdownNow();
        recoveryFetchers.shutdownNow();
        storageExecutor.shutdownNow();
        replicationServerClient.close();
    }

    private record Missing(String publisherKeyId, long sequence, String eventKey) { }
    public record CatchUpTopicStatus(String state, int fetched, int stored, int duplicates, int unavailable, int failures,
                                     Map<String, Long> firstUnresolvedGap, long attemptedAt, long completedAt) { }
}
