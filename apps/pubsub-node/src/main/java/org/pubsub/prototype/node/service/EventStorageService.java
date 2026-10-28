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
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.persistence.core.ReplicationServerClient;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.registry.replication.FileReplicationRegistry;
import org.pubsub.prototype.registry.replication.ReplicationRegistryReader;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.pubsub.prototype.constants.PersistenceConstants.CONCURRENCY_FIELD;
import static org.pubsub.prototype.util.validation.Validators.requirePositive;

/**
 * Handles event storage and recovery for a Pub/Sub node. Accepted events created
 * and published through this node are queued for storage on replication servers.
 * 
 * A local file holds the highest accepted sequence number and its timestamp for
 * each topic and publisher, including events received from peers.
 * That file contains no event payloads.
 * Recovery compares those values with the replication servers, fetches later
 * events, and passes them to the supplied callback.
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
    private final PersistenceEventValidator recoveryValidator;

    /**
     * Reads replication-server addresses from the configured local registry snapshot.
     * Each active registration supplies the host and port used for HTTP requests.
     */
    public EventStorageService(NodeConfig.PersistenceSection config, TopicStateProvider topics) {
        Path membershipPath = Path.of(config.membershipPath);
        Path deliveryStatePath = Path.of(config.deliveryStatePath);
        Duration connectionTimeout = Duration.ofMillis(config.connectionTimeoutMs);
        Duration requestTimeout = Duration.ofMillis(config.requestTimeoutMs);
        ReplicationRegistryReader registry = new FileReplicationRegistry(membershipPath);

        this.deliveryState = new DeliveryStateStore(deliveryStatePath);
        ReplicationHttpClient replicationHttpClient = new ReplicationHttpClient(connectionTimeout, requestTimeout, config.retries);
        this.replicationServerClient = new ReplicationServerClient(registry::activeServers, replicationHttpClient);
        requirePositive(config.recoveryConcurrency, CONCURRENCY_FIELD);
        this.recoveryValidator = new PersistenceEventValidator(topics);

        this.recoveryFetchers = Executors.newFixedThreadPool(config.recoveryConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "event-recovery-fetch");
            thread.setDaemon(true);
            return thread;
        });
        this.storageExecutor = AsyncUtil.singleThread("event-storage");

        NodeLog.init(LOG, "PERSISTENCE_READY membership={} deliveryState={} connectTimeout={} requestTimeout={} retries={} recoveryConcurrency={}",
                membershipPath, deliveryStatePath, connectionTimeout,
                requestTimeout, config.retries, config.recoveryConcurrency);
    }

        /**
     * Fetches events newer than the locally saved sequences. Calls the supplied callback for
     * each validated event, then saves its sequence after the callback returns. The callback is
     * used only for this recovery request.
     */
    public RecoveryResult recover(String topicId, Consumer<EventEnvelope> recoveredEventSink) {
        LOG.info("RECOVER_IN topicId={}", topicId);
        LOG.info("RECOVERY_STARTED topicId={}", topicId);
        List<Missing> missing = new ArrayList<>();
        long offlineTimestamp = deliveryState.latestDeliveredTimestamp(topicId);
        for (PublisherProgress remote :
                replicationServerClient.fetchLatestSequencesByPublisher(topicId, offlineTimestamp)) {
            DeliveryProgress local = deliveryState.progress(topicId, remote.publisherKeyId());
            for (long sequence = local.lastDeliveredSequenceNumber() + 1;
                 sequence <= remote.latestSequenceNumber(); sequence++) {
                missing.add(new Missing(remote.publisherKeyId(), sequence,
                        EventKeys.eventKey(topicId, remote.publisherKeyId(), sequence)));
                if (sequence == Long.MAX_VALUE) break;
            }
        }

        Map<String, CompletableFuture<Optional<StoredEvent>>> fetches = new LinkedHashMap<>();
        for (Missing item : missing) {
            fetches.put(item.eventKey, CompletableFuture.supplyAsync(
                    () -> replicationServerClient.findStoredEvent(topicId, item.eventKey), recoveryFetchers));
        }
        Map<String, List<Missing>> publishers = new LinkedHashMap<>();
        missing.forEach(item -> publishers.computeIfAbsent(item.publisherKeyId, ignored -> new ArrayList<>()).add(item));
        List<String> delivered = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        for (List<Missing> publisherMissing : publishers.values()) {
            publisherMissing.sort(Comparator.comparingLong(Missing::sequence));
            boolean gap = false;
            for (Missing item : publisherMissing) {
                Optional<StoredEvent> found = fetches.get(item.eventKey).join();
                if (gap || found.isEmpty()) {
                    gap = true;
                    unavailable.add(item.eventKey);
                    continue;
                }
                StoredEvent record = found.orElseThrow();
                EventEnvelope event = record.eventEnvelope();
                if (!item.eventKey.equals(record.eventKey())
                        || event.sequenceNumber() != item.sequence
                        || !item.publisherKeyId.equals(EventCrypto.publisherKeyId(event))) {
                    gap = true;
                    unavailable.add(item.eventKey);
                    continue;
                }
                recoveryValidator.validate(event);
                LOG.info("RECOVERY_EVENT_FOUND eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                        item.eventKey, event.eventId(), topicId, item.publisherKeyId, item.sequence);
                recoveredEventSink.accept(event);
                deliveryState.recordLatestAcceptedSequence(topicId, item.publisherKeyId, item.sequence, event.timestamp());
                delivered.add(item.eventKey);
                LOG.info("RECOVERY_EVENT_DELIVERED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                        item.eventKey, event.eventId(), topicId, item.publisherKeyId, item.sequence);
            }
        }
        LOG.info("RECOVERY_COMPLETED topicId={} missingCount={} deliveredCount={} unavailableCount={}",
                topicId, missing.size(), delivered.size(), unavailable.size());
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
    public void saveLocalRecoveryProgress(EventEnvelope event) {
        String publisherKeyId = EventCrypto.publisherKeyId(event);
        LOG.info("DELIVERY_STATE_IN eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                event.eventId(), event.topicId(), publisherKeyId, event.sequenceNumber());
        boolean updated = deliveryState.recordLatestAcceptedSequence(
                event.topicId(), publisherKeyId, event.sequenceNumber(), event.timestamp());
        LOG.info("DELIVERY_STATE_OUT eventId={} result={}", event.eventId(), updated ? "updated" : "unchanged");
    }

    /** Returns the locally saved sequence and timestamp for each topic and publisher. */
    public Object deliveryState() {
        Object snapshot = deliveryState.snapshot();
        LOG.info("DELIVERY_STATE_READ result={}", snapshot);
        return snapshot;
    }

    @Override
    public void close() {
        recoveryFetchers.shutdownNow();
        storageExecutor.shutdownNow();
        replicationServerClient.close();
    }

    private record Missing(String publisherKeyId, long sequence, String eventKey) { }
}
