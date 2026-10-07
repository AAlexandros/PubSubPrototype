package org.pubsub.prototype.node.runtimes;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.node.util.AsyncUtil;
import org.pubsub.prototype.persistence.RecoveryResult;
import org.pubsub.prototype.persistence.ReplicationRegistryReader;
import org.pubsub.prototype.persistence.core.DeliveryStateStore;
import org.pubsub.prototype.persistence.core.FileReplicationRegistry;
import org.pubsub.prototype.persistence.core.PersistenceClient;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.persistence.core.RecoveryService;
import org.pubsub.prototype.persistence.core.ReplicationHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class PersistenceRuntime implements AutoCloseable {
    // Standart logger
    private static final Logger LOG = LoggerFactory.getLogger(PersistenceRuntime.class);
    
    private final DeliveryStateStore deliveryState;
    private final ReplicationHttpClient http;
    private final PersistenceClient client;
    private final ExecutorService persistenceExecutor;
    private final RecoveryService recovery;

    public PersistenceRuntime(Settings settings, TopicStateProvider topics) {
        this.deliveryState = new DeliveryStateStore(settings.deliveryStatePath());
        this.http = new ReplicationHttpClient(settings.connectionTimeout(), settings.requestTimeout(), settings.retries());
        ReplicationRegistryReader registry = new FileReplicationRegistry(settings.membershipPath());
        this.client = new PersistenceClient(registry::activeServers, http);
        this.recovery = new RecoveryService(client, deliveryState,
                new PersistenceEventValidator(topics), settings.recoveryConcurrency());
        this.persistenceExecutor = AsyncUtil.singleThread("event-persistence");
    }

    public void persist(EventEnvelope event) {
        persistenceExecutor.execute(() -> {
            try {
                client.store(event);
            } catch (RuntimeException ex) {
                LOG.warn("EVENT_PERSIST_FAILED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} reason={}", org.pubsub.prototype.persistence.EventKeys.eventKey(event), event.eventId(), event.topicId(), EventCrypto.publisherKeyId(event), event.sequenceNumber(), ex.getMessage());
            }
        });
    }

    public void recordDelivered(EventEnvelope event, String publisherKeyId) {
        deliveryState.recordDelivered(event.topicId(), publisherKeyId, event.sequenceNumber(), event.timestamp());
    }

    /** The recovered-event sink is scoped to this recovery request and is not retained. */
    public RecoveryResult recover(String topicId, Consumer<EventEnvelope> recoveredEventSink) {
        return recovery.recover(topicId, recoveredEventSink);
    }

    public Object deliveryState() {
        return deliveryState.snapshot();
    }

    @Override
    public void close() {
        recovery.close();
        persistenceExecutor.shutdownNow();
        http.close();
    }

    public record Settings(Path membershipPath, Path deliveryStatePath, Duration connectionTimeout,
                           Duration requestTimeout, int retries, int recoveryConcurrency) {
    }
}
