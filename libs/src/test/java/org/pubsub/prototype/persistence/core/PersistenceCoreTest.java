package org.pubsub.prototype.persistence.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.EventKeys;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.ReplicationServerState;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
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
    void registryAuthorizesControllerFiltersAndReconstructs() {
        Path file = temp.resolve("servers.json");
        FileReplicationRegistry registry = new FileReplicationRegistry(file);
        ReplicationServerState state = new ReplicationServerState("ab".repeat(32), "operator-1", "localhost",
                9000, 2, 9, true);
        registry.registerServer(state, "operator-1");
        assertThrows(SecurityException.class, () -> registry.unregisterServer(state.serverId(), "operator-2"));
        assertEquals(List.of(state), new FileReplicationRegistry(file).queryServers(false));
        registry.unregisterServer(state.serverId(), "operator-1");
        assertTrue(registry.queryServers(false).isEmpty());
        assertEquals(1, new FileReplicationRegistry(file).queryServers(true).size());
    }

    @Test
    void fileStorageSurvivesRestartAdvancesProgressAndExpires() throws Exception {
        AtomicLong epoch = new AtomicLong(5);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);
        TopicState topic = topic(2, 3, List.of());
        EventEnvelope event = event(TOPIC, keys(), 0, 1000);
        FileEventStore first = new FileEventStore(temp, clock, epoch::get);
        StoredEvent stored = first.store(event, topic);
        assertEquals(8, stored.expiresAfterEpoch());
        assertTrue(new FileEventStore(temp, clock, epoch::get).get(stored.eventKey()).isPresent());
        String publisher = EventCrypto.publisherKeyId(event);
        assertTrue(first.updateProgress(TOPIC, new PublisherProgress(publisher, 2, 20)));
        assertFalse(first.updateProgress(TOPIC, new PublisherProgress(publisher, 1, 10)));
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

    @Test
    void recoveryFetchesInOrderSuppressesDuplicatesAndSurvivesRestart() throws Exception {
        KeyPair publisher = keys();
        List<EventEnvelope> events = List.of(event(TOPIC, publisher, 0, 10), event(TOPIC, publisher, 1, 11),
                event(TOPIC, publisher, 2, 12));
        String publisherId = EventCrypto.publisherKeyId(publisher.getPublic());
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/topics/" + TOPIC + "/publishers", exchange -> json(exchange,
                List.of(new PublisherProgress(publisherId, 2, 12))));
        for (EventEnvelope event : events) {
            StoredEvent stored = new StoredEvent(EventKeys.eventKey(event), event, Instant.EPOCH, 10, 0, 10);
            fake.createContext("/v1/events/" + stored.eventKey(), exchange -> json(exchange, stored));
        }
        fake.start();
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(1), 0)) {
            PersistenceClient client = new PersistenceClient(() -> List.of(new ReplicationServer("aa".repeat(32),
                    "127.0.0.1", fake.getAddress().getPort())), http);
            Path stateFile = temp.resolve("delivery.json");
            DeliveryStateStore initial = new DeliveryStateStore(stateFile);
            initial.recordDelivered(TOPIC, publisherId, 0, 10);
            assertEquals(10, initial.latestDeliveredTimestamp(TOPIC));
            List<Long> delivered = new ArrayList<>();
            PersistenceEventValidator validator = new PersistenceEventValidator(id -> java.util.Optional.of(topic(1, 10, List.of())));
            try (RecoveryService recovery = new RecoveryService(client, new DeliveryStateStore(stateFile), validator,
                    event -> delivered.add(event.sequenceNumber()), 2)) {
                assertEquals(2, recovery.recover(TOPIC).deliveredCount());
                assertEquals(List.of(1L, 2L), delivered);
            }
            try (RecoveryService restarted = new RecoveryService(client, new DeliveryStateStore(stateFile), validator,
                    event -> fail("duplicate delivery"), 2)) {
                assertEquals(0, restarted.recover(TOPIC).deliveredCount());
            }
        } finally {
            fake.stop(0);
        }
    }

    private static void json(com.sun.net.httpserver.HttpExchange exchange, Object value) throws java.io.IOException {
        byte[] bytes = PersistenceJson.MAPPER.writeValueAsBytes(value);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
        return unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keys.getPrivate()), EventCrypto.eventId(unsigned));
    }
}
