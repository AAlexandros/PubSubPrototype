package org.pubsub.prototype.persistence.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.registry.replication.ReplicationServer;
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
        List<ReplicationServer> servers = List.of(server("10"), server("80"), server("f0"));
        assertEquals(List.of(server("80"), server("10")),
                DhtAssignment.responsibleServers("70" + "00".repeat(31), servers, 2));
        assertEquals(List.of(server("f0"), server("10")),
                DhtAssignment.responsibleServers("f8" + "00".repeat(31), servers, 2));
        assertEquals(List.of(server("10"), server("f0")),
                DhtAssignment.responsibleServers("00".repeat(32), List.of(server("f0"), server("10")), 2));
        assertEquals(servers.size(), DhtAssignment.responsibleServers("00".repeat(32), servers, 10).size());
        List<ReplicationServer> reversed = new ArrayList<>(servers);
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
        FileEventStore first = new FileEventStore(temp, clock, epoch::get);
        StoredEvent stored = first.store(event, topic);
        assertEquals(8, stored.expiresAfterEpoch());
        assertTrue(new FileEventStore(temp, clock, epoch::get).get(stored.eventKey()).isPresent());
        assertTrue(first.updateProgress(TOPIC, PublisherProgress.fromEvent(event(TOPIC, keyPair, 2, 20))));
        assertFalse(first.updateProgress(TOPIC, PublisherProgress.fromEvent(event(TOPIC, keyPair, 1, 10))));
        assertEquals(2, new FileEventStore(temp, clock, epoch::get).publisherProgress(TOPIC).getFirst().latestSequenceNumber());
        epoch.set(8);
        assertTrue(first.get(stored.eventKey()).isEmpty());
        assertFalse(first.containsRaw(stored.eventKey()));
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

    private static ReplicationServer server(String prefix) {
        return new ReplicationServer(prefix + "00".repeat(31), "server-" + prefix, 9000);
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
