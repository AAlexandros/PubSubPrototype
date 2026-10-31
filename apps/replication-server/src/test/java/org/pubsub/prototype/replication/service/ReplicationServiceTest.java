package org.pubsub.prototype.replication.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.auth.SignedHttpRequest;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.persistence.maintenance.ReplicaRecordType;
import org.pubsub.prototype.persistence.maintenance.ReplicaRepairRequest;
import org.pubsub.prototype.registry.replication.ReplicationMembership;
import org.pubsub.prototype.registry.replication.ReplicationServer;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.replication.health.PeerFailureDetector;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.interfaces.EdECPrivateKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationServiceTest {
    @TempDir Path temp;

    @Test
    void lookupChecksOnlyTheTopicReplicasAndOneExtraCandidate() throws Exception {
        String topicId = "10".repeat(32);
        TopicState topic = new TopicState(new TopicId(topicId), "topic", List.of("owner"),
                List.of(), List.of(), 1, 5, true);
        List<HttpServer> listeners = new ArrayList<>();
        List<ReplicationServer> members = new ArrayList<>();
        AtomicInteger probes = new AtomicInteger();
        try {
            for (int i = 1; i <= 4; i++) {
                HttpServer listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                listener.createContext("/v1/replication/events", exchange -> {
                    probes.incrementAndGet();
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
                listener.start();
                listeners.add(listener);
                members.add(new ReplicationServer(String.format("%02x", i).repeat(32),
                        "127.0.0.1", listener.getAddress().getPort()));
            }
            ReplicationServer self = new ReplicationServer("ff".repeat(32), "127.0.0.1", 9999);
            SignedHttpRequest requestSigner = testRequestSigner();
            try (ReplicationHttpClient http = new ReplicationHttpClient(
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 0, requestSigner)) {
                TopicStateProvider topics = id -> Optional.of(topic);
                ReplicationService service = new ReplicationService(self, () -> members,
                        new PersistenceEventValidator(topics), topics,
                        new FileEventStore(temp, Clock.systemUTC(), () -> 1), http,
                        new PeerFailureDetector(3));
                assertTrue(service.lookup(topicId, "ab".repeat(32)).isEmpty());
                assertEquals(2, probes.get());
            }
        } finally {
            listeners.forEach(listener -> listener.stop(0));
        }
    }

    private SignedHttpRequest testRequestSigner() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encodedPublic = keys.getPublic().getEncoded();
        byte[] verification = cardanoKey(Arrays.copyOfRange(encodedPublic,
                encodedPublic.length - 32, encodedPublic.length));
        byte[] signing = cardanoKey(((EdECPrivateKey) keys.getPrivate()).getBytes().orElseThrow());
        Path verificationFile = temp.resolve("request.vkey");
        Path signingFile = temp.resolve("request.skey");
        Files.writeString(verificationFile, "{\"cborHex\":\"" + HexFormat.of().formatHex(verification) + "\"}");
        Files.writeString(signingFile, "{\"cborHex\":\"" + HexFormat.of().formatHex(signing) + "\"}");
        return SignedHttpRequest.load(signingFile, verificationFile, EventCrypto.sha256Hex(verification));
    }

    private static byte[] cardanoKey(byte[] raw) {
        byte[] cbor = new byte[34];
        cbor[0] = 0x58;
        cbor[1] = 0x20;
        System.arraycopy(raw, 0, cbor, 2, 32);
        return cbor;
    }

    @Test
    void storesLooksUpAndAdvancesTopicLogOnResponsibleServer() throws Exception {
        String topicId = "10".repeat(32);
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EventEnvelope unsigned = EventEnvelope.unsigned(topicId,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()), 4, 99,
                Base64.getEncoder().encodeToString("test".getBytes(StandardCharsets.UTF_8)));
        EventEnvelope event = unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keyPair.getPrivate()),
                EventCrypto.eventId(unsigned))
                .withProgressSignature(EventCrypto.signProgress(unsigned, keyPair.getPrivate()));
        TopicState topic = new TopicState(new TopicId(topicId), "topic", List.of("owner"), List.of(), List.of(), 1, 5, true);
        ReplicationServer self = new ReplicationServer("20".repeat(32), "localhost", 9999);
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1);
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofMillis(10), Duration.ofMillis(10), 0)) {
            TopicStateProvider topics = id -> Optional.of(topic);
            ReplicationService service = new ReplicationService(self, () -> List.of(self),
                    new PersistenceEventValidator(topics), topics, store, http,
                    new PeerFailureDetector(3));
            assertEquals(EventKeys.eventKey(event), service.persist(event).eventKey());
            assertEquals(event, service.lookup(topicId, EventKeys.eventKey(event)).orElseThrow().eventEnvelope());
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
        var membership = (org.pubsub.prototype.registry.replication.ReplicationMembership) () -> List.of(self);
        TopicState topic = new TopicState(new TopicId(topicId), "topic", List.of("owner"), List.of(),
                List.of(), 1, 5, true);
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofMillis(10), Duration.ofMillis(10), 0)) {
            PeerFailureDetector failures = new PeerFailureDetector(3);
            TopicStateProvider topics = id -> Optional.of(topic);
            ReplicationService service = new ReplicationService(self, membership,
                    new PersistenceEventValidator(topics), topics, store, http, failures);
            ReplicaMaintenanceService maintenance = new ReplicaMaintenanceService(self, membership, service, store,
                    http, () -> List.of(topic), failures, Duration.ofMillis(10));
            ReplicaRepairRequest request = new ReplicaRepairRequest(ReplicaRecordType.TOPIC_LOG,
                    EventKeys.topicLogKey(topicId), topicId, List.of(self.serverId()), List.of());

            maintenance.requestRepair(request);
            maintenance.requestRepair(request);

            assertEquals(1, maintenance.status().repairQueue().size());
        }
    }
}
