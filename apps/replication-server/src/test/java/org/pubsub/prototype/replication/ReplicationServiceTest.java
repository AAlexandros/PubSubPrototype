package org.pubsub.prototype.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.EventKeys;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.ReplicaRecordType;
import org.pubsub.prototype.persistence.ReplicaRepairRequest;
import org.pubsub.prototype.persistence.core.FileEventStore;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.persistence.core.ReplicationHttpClient;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationServiceTest {
    @TempDir Path temp;

    @Test
    void storesLooksUpAndAdvancesTopicLogOnResponsibleServer() throws Exception {
        String topicId = "10".repeat(32);
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EventEnvelope unsigned = EventEnvelope.unsigned(topicId,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()), 4, 99,
                Base64.getEncoder().encodeToString("test".getBytes(StandardCharsets.UTF_8)));
        EventEnvelope event = unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keyPair.getPrivate()),
                EventCrypto.eventId(unsigned));
        TopicState topic = new TopicState(new TopicId(topicId), "topic", List.of("owner"), List.of(), List.of(), 1, 5, true);
        ReplicationServer self = new ReplicationServer("20".repeat(32), "localhost", 9999);
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1);
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofMillis(10), Duration.ofMillis(10), 0)) {
            ReplicationService service = new ReplicationService(self, () -> List.of(self),
                    new PersistenceEventValidator(id -> Optional.of(topic)), store, http);
            assertEquals(EventKeys.eventKey(event), service.persist(event).eventKey());
            assertEquals(event, service.lookup(EventKeys.eventKey(event)).orElseThrow().eventEnvelope());
            assertEquals(4, service.publisherProgress(topicId).getFirst().latestSequenceNumber());
            assertEquals(1, service.publisherProgress(topicId, 98).size());
            assertTrue(service.publisherProgress(topicId, 99).isEmpty());
            service.persist(event);
            assertEquals(1, store.eventKeys().size());
        }
    }

    @Test
    void duplicateFailureRepairNotificationsShareOneQueueEntry() {
        String topicId = "10".repeat(32);
        ReplicationServer self = new ReplicationServer("20".repeat(32), "localhost", 9999);
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1);
        var membership = (org.pubsub.prototype.persistence.core.ReplicationMembership) () -> List.of(self);
        TopicState topic = new TopicState(new TopicId(topicId), "topic", List.of("owner"), List.of(),
                List.of(), 1, 5, true);
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofMillis(10), Duration.ofMillis(10), 0)) {
            ReplicationService service = new ReplicationService(self, membership,
                    new PersistenceEventValidator(id -> Optional.of(topic)), store, http);
            ReplicaMaintenanceManager maintenance = new ReplicaMaintenanceManager(self, membership, service, store,
                    http, () -> List.of(topic), 3, Duration.ofMillis(10));
            ReplicaRepairRequest request = new ReplicaRepairRequest(ReplicaRecordType.TOPIC_LOG,
                    EventKeys.topicLogKey(topicId), topicId, membership.version(), List.of(self),
                    List.of(self.serverId()), List.of(), "failure");

            maintenance.requestRepair(request);
            maintenance.requestRepair(request);

            assertEquals(1, maintenance.status().repairQueue().size());
        }
    }
}
