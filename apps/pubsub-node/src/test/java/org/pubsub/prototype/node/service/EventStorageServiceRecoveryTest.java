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
import org.pubsub.prototype.persistence.adapter.NodeEventStore;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.registry.replication.ReplicationServerEndpoint;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.util.files.JsonSupport;
import org.pubsub.prototype.util.files.AtomicFiles;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class EventStorageServiceRecoveryTest {
    private static final String TOPIC = "11".repeat(32);
    @TempDir Path temp;

    @Test
    void startupAutomaticallyCatchesUpOnlyRestoredSubscriptions() throws Exception {
        KeyPair publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EventEnvelope event = event(publisher, 0, 10);
        StoredEvent stored = new StoredEvent(EventKeys.eventKey(event), event, Instant.EPOCH, 10, 0, 10);
        AtomicInteger queries = new AtomicInteger();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/client/topics/" + TOPIC + "/publishers", exchange -> {
            queries.incrementAndGet();
            json(exchange, List.of(PublisherProgress.fromEvent(event)));
        });
        fake.createContext("/v1/client/events/" + stored.eventKey(), exchange -> json(exchange, stored));
        fake.start();
        try {
            var topicProvider = (org.pubsub.prototype.event.TopicStateProvider) id -> Optional.of(new TopicState(
                    new TopicId(TOPIC), "test", List.of("owner"), List.of(), List.of(), 1, 10, true));
            var members = (org.pubsub.prototype.registry.replication.ReplicationMembershipProvider) () -> List.of(
                    new ReplicationServerEndpoint("aa".repeat(32), "127.0.0.1",
                            fake.getAddress().getPort()));
            try (EventStorageService unsubscribed = new EventStorageService(config(temp.resolve("unsubscribed/delivery.json")),
                    topicProvider, members)) {
                unsubscribed.startAutomaticCatchUp(Set.of(), ignored -> fail("unsubscribed event"));
                awaitCompletion(unsubscribed);
                assertEquals(0, queries.get());
            }
            try (EventStorageService subscribed = new EventStorageService(config(temp.resolve("subscribed/delivery.json")),
                    topicProvider, members)) {
                subscribed.startAutomaticCatchUp(Set.of(TOPIC), ignored -> { });
                awaitCompletion(subscribed);
                assertEquals(event, subscribed.localEvent(event.eventId()).orElseThrow());
                assertEquals(1, queries.get());
            }
        } finally {
            fake.stop(0);
        }
    }

    private static void awaitCompletion(EventStorageService storage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Boolean.TRUE.equals(storage.catchUpStatus().get("initialCompleted")) && System.currentTimeMillis() < deadline)
            Thread.sleep(25);
        assertEquals(true, storage.catchUpStatus().get("initialCompleted"));
    }

    @Test
    void localDeliveryIsContiguousAndStoreFailureCannotAdvanceCursor() throws Exception {
        KeyPair publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        List<EventEnvelope> events = List.of(event(publisher, 0, 10), event(publisher, 1, 11), event(publisher, 2, 12));
        String publisherId = EventCrypto.publisherKeyId(publisher.getPublic());
        NodeConfig.PersistenceSection config = config(temp.resolve("ordered/delivery.json"));
        try (EventStorageService storage = new EventStorageService(config, id -> Optional.empty(), List::of)) {
            storage.storeLocalSubscriberEvent(events.get(2), "LIVE");
            assertEquals(-1, new DeliveryStateStore(Path.of(config.deliveryStatePath))
                    .progress(TOPIC, publisherId).lastDeliveredSequenceNumber());
            storage.storeLocalSubscriberEvent(events.get(0), "LIVE");
            assertEquals(0, new DeliveryStateStore(Path.of(config.deliveryStatePath))
                    .progress(TOPIC, publisherId).lastDeliveredSequenceNumber());
            storage.storeLocalSubscriberEvent(events.get(1), "CATCH_UP");
            assertEquals(2, new DeliveryStateStore(Path.of(config.deliveryStatePath))
                    .progress(TOPIC, publisherId).lastDeliveredSequenceNumber());
        }
        AtomicFiles.write(Path.of(config.deliveryStatePath), ("{\"" + TOPIC + "\":{\"" + publisherId
                + "\":{\"lastDeliveredSequenceNumber\":0,\"lastDeliveredTimestamp\":10}}}").getBytes(StandardCharsets.UTF_8));
        try (EventStorageService restarted = new EventStorageService(config, id -> Optional.empty(), List::of)) {
            assertEquals(events.get(2), restarted.localEvent(TOPIC, publisherId, 2).orElseThrow());
            assertEquals(2, new DeliveryStateStore(Path.of(config.deliveryStatePath))
                    .progress(TOPIC, publisherId).lastDeliveredSequenceNumber());
        }

        NodeConfig.PersistenceSection failing = config(temp.resolve("failure/delivery.json"));
        try (EventStorageService storage = new EventStorageService(failing, id -> Optional.empty(), List::of)) {
            Files.writeString(temp.resolve("failure/events").resolve(TOPIC), "blocked");
            assertThrows(IllegalStateException.class, () -> storage.storeLocalSubscriberEvent(events.get(0), "LIVE"));
            assertEquals(-1, new DeliveryStateStore(Path.of(failing.deliveryStatePath))
                    .progress(TOPIC, publisherId).lastDeliveredSequenceNumber());
        }
    }

    @Test
    void recoveryStopsAtUnavailableSequenceAndReportsFirstGap() throws Exception {
        KeyPair publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        List<EventEnvelope> events = List.of(event(publisher, 0, 10), event(publisher, 1, 11), event(publisher, 2, 12));
        String publisherId = EventCrypto.publisherKeyId(publisher.getPublic());
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/client/topics/" + TOPIC + "/publishers", exchange ->
                json(exchange, List.of(PublisherProgress.fromEvent(events.get(2)))));
        fake.createContext("/v1/client/events/" + EventKeys.eventKey(events.get(1)), exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        StoredEvent stored = new StoredEvent(EventKeys.eventKey(events.get(2)), events.get(2), Instant.EPOCH, 10, 0, 10);
        fake.createContext("/v1/client/events/" + stored.eventKey(), exchange -> json(exchange, stored));
        fake.start();
        try {
            NodeConfig.PersistenceSection config = config(temp.resolve("gap/delivery.json"));
            new NodeEventStore(temp.resolve("gap/events")).store(events.get(0));
            try (EventStorageService storage = new EventStorageService(config, id -> Optional.of(new TopicState(
                    new TopicId(TOPIC), "test", List.of("owner"), List.of(), List.of(), 1, 10, true)),
                    () -> List.of(new ReplicationServerEndpoint("aa".repeat(32), "127.0.0.1",
                            fake.getAddress().getPort())))) {
                assertEquals(0, storage.recover(TOPIC, event -> fail("event after gap delivered")).deliveredCount());
                assertEquals(0, new DeliveryStateStore(Path.of(config.deliveryStatePath))
                        .progress(TOPIC, publisherId).lastDeliveredSequenceNumber());
                assertEquals(1L, storage.catchUpStatus().get("topics") instanceof java.util.Map<?, ?> statuses
                        ? ((EventStorageService.CatchUpTopicStatus) statuses.get(TOPIC)).firstUnresolvedGap().get(publisherId)
                        : -1L);
            }
        } finally {
            fake.stop(0);
        }
    }

    private static NodeConfig.PersistenceSection config(Path stateFile) {
        NodeConfig.PersistenceSection config = new NodeConfig.PersistenceSection();
        config.deliveryStatePath = stateFile.toString();
        config.connectionTimeoutMs = 1_000L;
        config.requestTimeoutMs = 1_000L;
        config.retries = 0;
        config.recoveryConcurrency = 2;
        return config;
    }

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
            List<ReplicationServerEndpoint> members = List.of(new ReplicationServerEndpoint(
                    "aa".repeat(32), "127.0.0.1", fake.getAddress().getPort()));
            Path stateFile = temp.resolve("delivery.json");
            new NodeEventStore(temp.resolve("events")).store(events.get(0));
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
                    () -> members)) {
                assertEquals(2, storage.recover(
                        TOPIC, event -> delivered.add(event.sequenceNumber())).deliveredCount());
                assertEquals("topicId=" + TOPIC, eventLookupQuery.get());
                assertEquals(List.of(1L, 2L), delivered);
            }
            try (EventStorageService restarted = new EventStorageService(config, id ->
                    java.util.Optional.of(new TopicState(new TopicId(TOPIC), "test", List.of("owner"),
                            List.of(), List.of(), 1, 10, true)),
                    () -> members)) {
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
