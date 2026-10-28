package org.pubsub.prototype.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.node.service.NavigationService;
import org.pubsub.prototype.node.service.EventProcessingService;
import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.transport.PubSubTransport;
import org.pubsub.prototype.transport.TransportConfig;
import org.pubsub.prototype.transport.TransportListener;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationIntegrationTest {
    private static final List<String> TOPICS = List.of("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64));

    @TempDir
    Path dir;

    @Test
    void realSocketGossipConvergesNavigationViewsAndEstablishesDynamicConnection() throws Exception {
        int p1 = port();
        int p2 = port();
        NodeIdentity identityA = IdentityStore.loadOrCreate(dir.resolve("a"));
        NodeIdentity identityB = IdentityStore.loadOrCreate(dir.resolve("b"));
        NodeEndpoint peerA = new NodeEndpoint(identityA.nodeId().value(), "127.0.0.1", p1);
        NodeEndpoint peerB = new NodeEndpoint(identityB.nodeId().value(), "127.0.0.1", p2);

        try (Node a = new Node("a", p1, identityA, List.of(TOPICS.get(0)), List.of(peerB));
             Node b = new Node("b", p2, identityB, List.of(TOPICS.get(0)), List.of(peerA))) {
            a.start();
            b.start();

            await(() -> !a.service.navigation().view().isEmpty() && !b.service.navigation().view().isEmpty());
            await(() -> a.transport.activePeerCount() == 1 && b.transport.activePeerCount() == 1);

            assertTrue(knows(a, peerB));
            assertTrue(knows(b, peerA));
        }
    }

    @Test
    void subscribePersistsAndUpdatesFingerTopics() throws Exception {
        int port = port();
        NodeIdentity identity = IdentityStore.loadOrCreate(dir.resolve("solo"));
        try (Node node = new Node("solo", port, identity, List.of(), List.of())) {
            node.start();
            node.service.subscribe(TOPICS.get(0));

            assertTrue(node.service.navigation().subscriptions().contains(TOPICS.get(0)));
            assertTrue(SubscriptionStore.loadOrCreate(dir.resolve("solo-subscriptions.json")).snapshot().contains(TOPICS.get(0)));
        }
    }

    private static boolean knows(Node node, NodeEndpoint peer) {
        return node.service.navigation().view().values().stream()
                .flatMap(List::stream)
                .anyMatch(candidate -> candidate.nodeId().equals(peer.nodeId()));
    }

    private final class Node implements AutoCloseable {
        final NavigationService service;
        final PubSubTransport transport;

        Node(String name, int port, NodeIdentity identity, List<String> subscriptions, List<NodeEndpoint> samples) {
            SubscriptionStore store = SubscriptionStore.loadOrCreate(dir.resolve(name + "-subscriptions.json"));
            subscriptions.forEach(store::add);
            NodeConfig.NavigationSection config = new NodeConfig.NavigationSection();
            config.capacity = 2;
            config.routingBase = 2;
            config.cycleIntervalMs = 150L;
            config.staleAfterMs = 60_000L;
            service = new NavigationService(identity.nodeId().value(), "127.0.0.1", port, store,
                    config,
                    () -> TOPICS, () -> samples);
            EventProcessingService events = new EventProcessingService(identity, dir.resolve(name + "-events"), topicId -> Optional.empty());
            transport = new PubSubTransport(new TransportConfig(name, "127.0.0.1", port, List.of(), 100, 500, 50, 100),
                    identity, new NodeEndpoint(identity.nodeId().value(), "127.0.0.1", port),
                    new TransportListener() {
                        @Override
                        public void samplingReceived(NodeId peer, ProtocolMessage message) {
                            service.messageReceived(peer, message);
                        }
                    });
            service.attach(transport);
        }

        void start() throws Exception {
            transport.start();
            service.start();
        }

        @Override
        public void close() {
            service.close();
            transport.close();
        }
    }

    private static int port() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!check.getAsBoolean() && System.nanoTime() < end) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(check.getAsBoolean());
    }
}
