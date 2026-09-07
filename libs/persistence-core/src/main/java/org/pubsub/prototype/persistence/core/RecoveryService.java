package org.pubsub.prototype.persistence.core;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.DeliveryProgress;
import org.pubsub.prototype.persistence.EventKeys;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.RecoveryResult;
import org.pubsub.prototype.persistence.StoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

public final class RecoveryService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryService.class);
    private final PersistenceClient client;
    private final DeliveryStateStore state;
    private final PersistenceEventValidator validator;
    private final Consumer<EventEnvelope> delivery;
    private final ExecutorService fetchers;

    public RecoveryService(PersistenceClient client, DeliveryStateStore state,
                           PersistenceEventValidator validator, Consumer<EventEnvelope> delivery,
                           int concurrency) {
        if (concurrency <= 0) throw new IllegalArgumentException("concurrency must be positive");
        this.client = client;
        this.state = state;
        this.validator = validator;
        this.delivery = delivery;
        this.fetchers = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "event-recovery-fetch");
            thread.setDaemon(true);
            return thread;
        });
    }

    public RecoveryResult recover(String topicId) {
        LOG.info("RECOVERY_STARTED topicId={}", topicId);
        List<Missing> missing = new ArrayList<>();
        long offlineTimestamp = state.latestDeliveredTimestamp(topicId);
        for (PublisherProgress remote : client.publisherProgress(topicId, offlineTimestamp)) {
            DeliveryProgress local = state.progress(topicId, remote.publisherKeyId());
            for (long sequence = local.lastDeliveredSequenceNumber() + 1;
                 sequence <= remote.latestSequenceNumber(); sequence++) {
                missing.add(new Missing(remote.publisherKeyId(), sequence,
                        EventKeys.eventKey(topicId, remote.publisherKeyId(), sequence)));
                if (sequence == Long.MAX_VALUE) break;
            }
        }

        Map<String, CompletableFuture<Optional<StoredEvent>>> fetches = new LinkedHashMap<>();
        for (Missing item : missing) {
            fetches.put(item.eventKey, CompletableFuture.supplyAsync(() -> client.lookup(item.eventKey), fetchers));
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
                validator.validate(event);
                LOG.info("RECOVERY_EVENT_FOUND eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                        item.eventKey, event.eventId(), topicId, item.publisherKeyId, item.sequence);
                delivery.accept(event);
                state.recordDelivered(topicId, item.publisherKeyId, item.sequence, event.timestamp());
                delivered.add(item.eventKey);
                LOG.info("RECOVERY_EVENT_DELIVERED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                        item.eventKey, event.eventId(), topicId, item.publisherKeyId, item.sequence);
            }
        }
        LOG.info("RECOVERY_COMPLETED topicId={} missingCount={} deliveredCount={} unavailableCount={}",
                topicId, missing.size(), delivered.size(), unavailable.size());
        return new RecoveryResult(topicId, missing.size(), delivered.size(), delivered, unavailable);
    }

    @Override
    public void close() {
        fetchers.shutdownNow();
    }

    private record Missing(String publisherKeyId, long sequence, String eventKey) { }
}
