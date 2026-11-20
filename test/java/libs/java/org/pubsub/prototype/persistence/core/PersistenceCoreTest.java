package org.pubsub.prototype.persistence.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.maintenance.EventReplicaMetadata;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceCoreTest {
    private static final String TOPIC = "11".repeat(32);
    @TempDir Path temp;

    @Test
    void eventKeyUsesFixedBinaryEncodingAndEveryComponent() throws Exception {
        KeyPair one = keys();
        KeyPair two = keys();
        EventEnvelope event = event(TOPIC, one, 7, 1000);
        String publisher = EventCrypto.publisherKeyId(one.getPublic());
        assertEquals(EventCrypto.sha256Hex(java.nio.ByteBuffer.allocate(72)
                .put(java.util.HexFormat.of().parseHex(TOPIC))
                .put(java.util.HexFormat.of().parseHex(publisher)).putLong(7).array()), EventKeys.eventKey(event));
        assertNotEquals(EventKeys.eventKey(TOPIC, publisher, 7), EventKeys.eventKey("22".repeat(32), publisher, 7));
        assertNotEquals(EventKeys.eventKey(TOPIC, publisher, 7),
                EventKeys.eventKey(TOPIC, EventCrypto.publisherKeyId(two.getPublic()), 7));
        assertNotEquals(EventKeys.eventKey(TOPIC, publisher, 7), EventKeys.eventKey(TOPIC, publisher, 8));
    }

    @Test
    void dhtAssignmentIsDeterministicReplicatedAndWraps() {
        List<ReplicationServerEndpoint> servers = List.of(server("10"), server("80"), server("f0"));
        assertEquals(List.of(server("80"), server("10")),
                DhtAssignment.responsibleServers("70" + "00".repeat(31), servers, 2));
        assertEquals(List.of(server("f0"), server("10")),
                DhtAssignment.responsibleServers("f8" + "00".repeat(31), servers, 2));
        assertEquals(List.of(server("10"), server("f0")),
                DhtAssignment.responsibleServers("00".repeat(32), List.of(server("f0"), server("10")), 2));
        assertEquals(servers.size(), DhtAssignment.responsibleServers("00".repeat(32), servers, 10).size());
        List<ReplicationServerEndpoint> reversed = new ArrayList<>(servers);
        java.util.Collections.reverse(reversed);
        assertEquals(DhtAssignment.responsibleServers("44".repeat(32), servers, 2),
                DhtAssignment.responsibleServers("44".repeat(32), reversed, 2));
    }

    @Test
    void fileStorageSurvivesRestartAdvancesProgressAndExpires() throws Exception {
        AtomicLong epoch = new AtomicLong(5);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);
        TopicState topic = topic(2, 3, List.of());
        KeyPair keyPair = keys();
        EventEnvelope event = event(TOPIC, keyPair, 0, 1000);
        FileEventStore first = new FileEventStore(temp, clock, epoch::get, id -> java.util.Optional.of(topic));
        StoredEvent stored = first.createEventRecord(event, topic);
        first.storeEventReplica(stored);
        assertEquals(8, stored.expiresAfterEpoch());
        assertTrue(new FileEventStore(temp, clock, epoch::get, id -> java.util.Optional.of(topic))
                .findUnexpiredEventOrDeleteExpiredFile(stored.eventKey()).isPresent());
        assertTrue(first.updateProgress(TOPIC, PublisherProgress.fromEvent(event(TOPIC, keyPair, 2, 20))));
        assertFalse(first.updateProgress(TOPIC, PublisherProgress.fromEvent(event(TOPIC, keyPair, 1, 10))));
        assertEquals(2, new FileEventStore(temp, clock, epoch::get, id -> java.util.Optional.of(topic))
                .publisherProgress(TOPIC).getFirst().latestSequenceNumber());
        epoch.set(8);
        assertTrue(first.findUnexpiredEventOrDeleteExpiredFile(stored.eventKey()).isEmpty());
        assertFalse(first.containsRaw(stored.eventKey()));
    }

    @Test
    void replicaStorageRejectsFutureEpochAndInconsistentExpiry() throws Exception {
        TopicState topic = topic(1, 3, List.of());
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 5,
                id -> java.util.Optional.of(topic));
        EventEnvelope event = event(TOPIC, keys(), 3, 1000);
        StoredEvent valid = store.createEventRecord(event, topic);

        assertThrows(IllegalArgumentException.class, () -> new StoredEvent(valid.eventKey(), event,
                valid.storedAt(), 3, 5, 9));
        assertThrows(IllegalArgumentException.class, () -> new EventReplicaMetadata(valid.eventKey(), TOPIC,
                EventCrypto.publisherKeyId(event), event.sequenceNumber() + 1, 3, 5, 8));
        StoredEvent future = new StoredEvent(valid.eventKey(), event, valid.storedAt(), 3, 6, 9);
        assertThrows(IllegalArgumentException.class, () -> store.storeEventReplica(future));
        StoredEvent expired = new StoredEvent(valid.eventKey(), event, valid.storedAt(), 3, 2, 5);
        assertThrows(IllegalArgumentException.class, () -> store.storeEventReplica(expired));
        assertFalse(store.containsRaw(valid.eventKey()));
    }

    @Test
    void currentTopicRetentionOverridesReplicaMetadataAndUpdatesStoredEvents() throws Exception {
        AtomicLong epoch = new AtomicLong(5);
        AtomicLong retention = new AtomicLong(3);
        TopicState initialTopic = topic(1, 3, List.of());
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), epoch::get,
                id -> java.util.Optional.of(initialTopic.withRetentionPeriod(retention.get())));
        EventEnvelope event = event(TOPIC, keys(), 4, 1000);
        StoredEvent created = store.createEventRecord(event, initialTopic);
        StoredEvent claimed = new StoredEvent(created.eventKey(), event, created.storedAt(), 100, 5, 105);

        StoredEvent accepted = store.storeEventReplica(claimed);
        assertEquals(3, accepted.topicRetentionPeriod());
        assertEquals(8, accepted.expiresAfterEpoch());
        assertEquals(8, store.findUnexpiredEventOrDeleteExpiredFile(created.eventKey()).orElseThrow().expiresAfterEpoch());
        retention.set(5);
        epoch.set(8);
        StoredEvent extended = store.findUnexpiredEventOrDeleteExpiredFile(created.eventKey()).orElseThrow();
        assertEquals(5, extended.topicRetentionPeriod());
        assertEquals(10, extended.expiresAfterEpoch());
        retention.set(2);
        assertTrue(store.findUnexpiredEventOrDeleteExpiredFile(created.eventKey()).isEmpty());
        assertFalse(store.containsRaw(created.eventKey()));
    }

    @Test
    void validatorRejectsBadSignatureAndUnauthorizedPublisher() throws Exception {
        KeyPair publisher = keys();
        EventEnvelope valid = event(TOPIC, publisher, 0, 1);
        PersistenceEventValidator open = new PersistenceEventValidator(id -> java.util.Optional.of(topic(2, 3, List.of())));
        assertDoesNotThrow(() -> open.validate(valid));
        EventEnvelope bad = valid.withSignature(Base64.getEncoder().encodeToString(new byte[64]));
        assertThrows(IllegalArgumentException.class, () -> open.validate(bad));
        PersistenceEventValidator closed = new PersistenceEventValidator(id ->
                java.util.Optional.of(topic(2, 3, List.of("ff".repeat(32)))));
        assertThrows(IllegalArgumentException.class, () -> closed.validate(valid));
    }

    private static ReplicationServerEndpoint server(String prefix) {
        return new ReplicationServerEndpoint(prefix + "00".repeat(31), "server-" + prefix, 9000);
    }

    private static TopicState topic(int factor, long retention, List<String> publishers) {
        return new TopicState(new TopicId(TOPIC), "test", List.of("owner"), List.of(), publishers,
                factor, retention, true);
    }

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static EventEnvelope event(String topicId, KeyPair keys, long sequence, long timestamp) {
        EventEnvelope unsigned = EventEnvelope.unsigned(topicId,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), sequence, timestamp,
                Base64.getEncoder().encodeToString(("payload-" + sequence).getBytes(StandardCharsets.UTF_8)));
        return unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keys.getPrivate()), EventCrypto.eventId(unsigned))
                .withProgressSignature(EventCrypto.signProgress(unsigned, keys.getPrivate()));
    }
}
