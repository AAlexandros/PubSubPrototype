package org.pubsub.prototype.node.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.adapter.DeliveryStateStore;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.registry.replication.ReplicationServerState;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class EventStorageServiceRecoveryTest {
    private static final String TOPIC = "11".repeat(32);
    @TempDir Path temp;

    @Test
    void recoveryFetchesInOrderSuppressesDuplicatesAndSurvivesRestart() throws Exception {
        KeyPair publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        List<EventEnvelope> events = List.of(event(publisher, 0, 10), event(publisher, 1, 11),
                event(publisher, 2, 12));
        String publisherId = EventCrypto.publisherKeyId(publisher.getPublic());
        AtomicReference<String> eventLookupQuery = new AtomicReference<>();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/client/topics/" + TOPIC + "/publishers", exchange -> json(exchange,
                List.of(PublisherProgress.fromEvent(events.get(2)))));
        for (EventEnvelope event : events) {
            StoredEvent stored = new StoredEvent(EventKeys.eventKey(event), event, Instant.EPOCH, 10, 0, 10);
            fake.createContext("/v1/client/events/" + stored.eventKey(), exchange -> {
                eventLookupQuery.set(exchange.getRequestURI().getQuery());
                json(exchange, stored);
            });
        }
        fake.start();
        try {
            List<ReplicationServerState> members = List.of(new ReplicationServerState(
                    "aa".repeat(32), "operator-1", "127.0.0.1", fake.getAddress().getPort(), 0, 100, true));
            Path stateFile = temp.resolve("delivery.json");
            DeliveryStateStore initial = new DeliveryStateStore(stateFile);
            initial.recordLatestAcceptedSequence(TOPIC, publisherId, 0, 10);
            assertEquals(10, initial.latestDeliveredTimestamp(TOPIC));
            List<Long> delivered = new ArrayList<>();
            NodeConfig.PersistenceSection config = new NodeConfig.PersistenceSection();
            config.deliveryStatePath = stateFile.toString();
            config.connectionTimeoutMs = 1_000L;
            config.requestTimeoutMs = 1_000L;
            config.retries = 0;
            config.recoveryConcurrency = 2;
            try (EventStorageService storage = new EventStorageService(config, id ->
                    java.util.Optional.of(new TopicState(new TopicId(TOPIC), "test", List.of("owner"),
                            List.of(), List.of(), 1, 10, true)),
                    () -> members.stream().map(ReplicationServerState::endpoint).toList())) {
                assertEquals(2, storage.recover(
                        TOPIC, event -> delivered.add(event.sequenceNumber())).deliveredCount());
                assertEquals("topicId=" + TOPIC, eventLookupQuery.get());
                assertEquals(List.of(1L, 2L), delivered);
            }
            try (EventStorageService restarted = new EventStorageService(config, id ->
                    java.util.Optional.of(new TopicState(new TopicId(TOPIC), "test", List.of("owner"),
                            List.of(), List.of(), 1, 10, true)),
                    () -> members.stream().map(ReplicationServerState::endpoint).toList())) {
                assertEquals(0, restarted.recover(
                        TOPIC, event -> fail("duplicate delivery")).deliveredCount());
            }
        } finally {
            fake.stop(0);
        }
    }

    private static EventEnvelope event(KeyPair keys, long sequence, long timestamp) {
        EventEnvelope unsigned = EventEnvelope.unsigned(TOPIC,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), sequence, timestamp,
                Base64.getEncoder().encodeToString(("payload-" + sequence).getBytes(StandardCharsets.UTF_8)));
        return unsigned.withSignatureAndEventId(EventCrypto.sign(unsigned, keys.getPrivate()), EventCrypto.eventId(unsigned))
                .withProgressSignature(EventCrypto.signProgress(unsigned, keys.getPrivate()));
    }

    private static void json(HttpExchange exchange, Object value) throws IOException {
        byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(value);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
