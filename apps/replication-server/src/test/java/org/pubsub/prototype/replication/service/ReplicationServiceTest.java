package org.pubsub.prototype.replication.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.auth.ReplicationHttpRequestSignatures;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.adapter.FileEventStore;
import org.pubsub.prototype.persistence.adapter.ReplicationHttpClient;
import org.pubsub.prototype.persistence.core.DhtAssignment;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.persistence.core.PersistenceEventValidator;
import org.pubsub.prototype.persistence.maintenance.ReplicaRecordType;
import org.pubsub.prototype.persistence.maintenance.ReplicaRepairRequest;
import org.pubsub.prototype.registry.replication.ReplicationMembershipProvider;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
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
        List<ReplicationServerEndpoint> members = new ArrayList<>();
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
                members.add(new ReplicationServerEndpoint(String.format("%02x", i).repeat(32),
                        "127.0.0.1", listener.getAddress().getPort()));
            }
            ReplicationServerEndpoint self = new ReplicationServerEndpoint("ff".repeat(32), "127.0.0.1", 9999);
            ReplicationHttpRequestSignatures.RequestSigner requestSigner = testRequestSigner();
            try (ReplicationHttpClient http = new ReplicationHttpClient(
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 0, requestSigner)) {
                TopicStateProvider topics = id -> Optional.of(topic);
                ReplicationService service = new ReplicationService(self, () -> members,
                        new PersistenceEventValidator(topics), topics,
                        new FileEventStore(temp, Clock.systemUTC(), () -> 1, topics), http,
                        new PeerFailureDetector(3));
                assertTrue(service.lookupEvent(topicId, "ab".repeat(32)).isEmpty());
                assertEquals(2, probes.get());
            }
        } finally {
            listeners.forEach(listener -> listener.stop(0));
        }
    }

    private ReplicationHttpRequestSignatures.RequestSigner testRequestSigner() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encodedPublic = keys.getPublic().getEncoded();
        byte[] verification = cardanoKey(Arrays.copyOfRange(encodedPublic,
                encodedPublic.length - 32, encodedPublic.length));
        byte[] signing = cardanoKey(((EdECPrivateKey) keys.getPrivate()).getBytes().orElseThrow());
        Path verificationFile = temp.resolve("request.vkey");
        Path signingFile = temp.resolve("request.skey");
        Files.writeString(verificationFile, "{\"cborHex\":\"" + HexFormat.of().formatHex(verification) + "\"}");
        Files.writeString(signingFile, "{\"cborHex\":\"" + HexFormat.of().formatHex(signing) + "\"}");
        return ReplicationHttpRequestSignatures.loadSignerFromCardanoKeys(
                signingFile, verificationFile, EventCrypto.sha256Hex(verification));
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
        ReplicationServerEndpoint self = new ReplicationServerEndpoint("20".repeat(32), "localhost", 9999);
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1,
                id -> Optional.of(topic));
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofMillis(10), Duration.ofMillis(10), 0)) {
            TopicStateProvider topics = id -> Optional.of(topic);
            ReplicationService service = new ReplicationService(self, () -> List.of(self),
                    new PersistenceEventValidator(topics), topics, store, http,
                    new PeerFailureDetector(3));
            assertEquals(EventKeys.eventKey(event), service.persistEvent(event).eventKey());
            assertEquals(event, service.lookupEvent(topicId, EventKeys.eventKey(event)).orElseThrow().eventEnvelope());
            assertEquals(4, service.listPublisherProgress(topicId).getFirst().latestSequenceNumber());
            assertEquals(1, service.publisherProgress(topicId, 98).size());
            assertTrue(service.publisherProgress(topicId, 99).isEmpty());
            service.persistEvent(event);
            assertEquals(1, store.eventKeys().size());
        }
    }

    @Test
    void rejectsEventReplicaOutsideLocalResponsibility() throws Exception {
        String topicId = "10".repeat(32);
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EventEnvelope unsigned = EventEnvelope.unsigned(topicId,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()), 4, 99,
                Base64.getEncoder().encodeToString("test".getBytes(StandardCharsets.UTF_8)));
        EventEnvelope event = unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keyPair.getPrivate()),
                EventCrypto.eventId(unsigned))
                .withProgressSignature(EventCrypto.signProgress(unsigned, keyPair.getPrivate()));
        TopicState topic = new TopicState(new TopicId(topicId), "topic", List.of("owner"),
                List.of(), List.of(), 1, 5, true);
        ReplicationServerEndpoint first = new ReplicationServerEndpoint("20".repeat(32), "localhost", 9999);
        ReplicationServerEndpoint second = new ReplicationServerEndpoint("80".repeat(32), "localhost", 9998);
        List<ReplicationServerEndpoint> members = List.of(first, second);
        ReplicationServerEndpoint responsible = DhtAssignment.responsibleServers(EventKeys.eventKey(event), members, 1)
                .getFirst();
        ReplicationServerEndpoint self = responsible.equals(first) ? second : first;
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1,
                id -> Optional.of(topic));
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofMillis(10), Duration.ofMillis(10), 0)) {
            TopicStateProvider topics = id -> Optional.of(topic);
            ReplicationService service = new ReplicationService(self, () -> members,
                    new PersistenceEventValidator(topics), topics, store, http, new PeerFailureDetector(3));

            assertThrows(IllegalArgumentException.class,
                    () -> service.storeReplica(store.createEventRecord(event, topic)));
            assertTrue(store.eventKeys().isEmpty());
        }
    }

    @Test
    void duplicateFailureRepairNotificationsShareOneQueueEntry() {
        String topicId = "10".repeat(32);
        ReplicationServerEndpoint self = new ReplicationServerEndpoint("20".repeat(32), "localhost", 9999);
        FileEventStore store = new FileEventStore(temp, Clock.systemUTC(), () -> 1,
                id -> Optional.empty());
        var membership = (org.pubsub.prototype.registry.replication.ReplicationMembershipProvider) () -> List.of(self);
        TopicState topic = new TopicState(new TopicId(topicId), "topic", List.of("owner"), List.of(),
                List.of(), 1, 5, true);
        try (ReplicationHttpClient http = new ReplicationHttpClient(Duration.ofMillis(10), Duration.ofMillis(10), 0)) {
            PeerFailureDetector failures = new PeerFailureDetector(3);
            TopicStateProvider topics = id -> Optional.of(topic);
            ReplicationService service = new ReplicationService(self, membership,
                    new PersistenceEventValidator(topics), topics, store, http, failures);
            ReplicaMaintenanceService maintenance = new ReplicaMaintenanceService(self, membership, service, store,
                    http, () -> List.of(topic), failures, Duration.ofMillis(10), 1000, 1000);
            ReplicaRepairRequest request = new ReplicaRepairRequest(ReplicaRecordType.TOPIC_LOG,
                    EventKeys.topicLogKey(topicId), topicId, List.of(self.serverId()), List.of());

            maintenance.requestRepair(request);
            maintenance.requestRepair(request);

            assertEquals(1, maintenance.status().repairQueue().size());
        }
    }
}
