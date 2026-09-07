package org.pubsub.prototype.node;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.RecoveryResult;
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
import java.util.concurrent.Executors;

final class NodePersistenceRuntime implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NodePersistenceRuntime.class);
    private final DeliveryStateStore deliveryState;
    private final ReplicationHttpClient http;
    private final PersistenceClient client;
    private final PersistenceEventValidator validator;
    private final int recoveryConcurrency;
    private final ExecutorService persistenceExecutor;
    private RecoveryService recovery;

    NodePersistenceRuntime(NodeConfig.PersistenceSection config, Path identityPath, TopicStateProvider topics) {
        Path deliveryPath = config.deliveryStatePath == null
                ? identityPath.resolve("recovery-state.json") : Path.of(config.deliveryStatePath);
        this.deliveryState = new DeliveryStateStore(deliveryPath);
        this.http = new ReplicationHttpClient(Duration.ofMillis(config.connectionTimeoutMs),
                Duration.ofMillis(config.requestTimeoutMs), config.retries);
        FileReplicationRegistry registry = new FileReplicationRegistry(Path.of(config.membershipPath));
        this.client = new PersistenceClient(registry::activeServers, http);
        this.validator = new PersistenceEventValidator(topics);
        this.recoveryConcurrency = config.recoveryConcurrency;
        this.persistenceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-persistence");
            thread.setDaemon(true);
            return thread;
        });
    }

    void attachDelivery(NodeEventService events) {
        this.recovery = new RecoveryService(client, deliveryState, validator, events::acceptRecovered, recoveryConcurrency);
    }

    void persist(EventEnvelope event) {
        persistenceExecutor.execute(() -> {
            try {
                client.store(event);
            } catch (RuntimeException ex) {
                LOG.warn("EVENT_PERSIST_FAILED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={} reason={}",
                        org.pubsub.prototype.persistence.EventKeys.eventKey(event), event.eventId(), event.topicId(),
                        EventCrypto.publisherKeyId(event), event.sequenceNumber(), ex.getMessage());
            }
        });
    }

    void recordDelivered(EventEnvelope event, String publisherKeyId) {
        deliveryState.recordDelivered(event.topicId(), publisherKeyId, event.sequenceNumber(), event.timestamp());
    }

    RecoveryResult recover(String topicId) {
        if (recovery == null) throw new IllegalStateException("persistence delivery is not attached");
        return recovery.recover(topicId);
    }

    Object deliveryState() {
        return deliveryState.snapshot();
    }

    @Override
    public void close() {
        if (recovery != null) recovery.close();
        persistenceExecutor.shutdownNow();
        http.close();
    }
}
