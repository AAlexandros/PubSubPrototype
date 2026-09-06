package org.pubsub.prototype.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.navigation.SubscriptionStore;
import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.sampling.PeerDescriptor;
import org.pubsub.prototype.transport.PubSubTransport;
import org.pubsub.prototype.transport.TransportConfig;

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
        PeerDescriptor peerA = new PeerDescriptor(identityA.nodeId().value(), "127.0.0.1", p1);
        PeerDescriptor peerB = new PeerDescriptor(identityB.nodeId().value(), "127.0.0.1", p2);

        try (Node a = new Node("a", p1, identityA, List.of(TOPICS.get(0)), List.of(peerB));
             Node b = new Node("b", p2, identityB, List.of(TOPICS.get(0)), List.of(peerA))) {
            a.start();
            b.start();

            await(() -> !a.runtime.engine().view().isEmpty() && !b.runtime.engine().view().isEmpty());
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
            node.runtime.subscribe(TOPICS.get(0));

            assertTrue(node.runtime.engine().subscriptions().contains(TOPICS.get(0)));
            assertTrue(SubscriptionStore.loadOrCreate(dir.resolve("solo-subscriptions.txt")).snapshot().contains(TOPICS.get(0)));
        }
    }

    private static boolean knows(Node node, PeerDescriptor peer) {
        return node.runtime.engine().view().values().stream()
                .flatMap(List::stream)
                .anyMatch(candidate -> candidate.nodeId().equals(peer.nodeId()));
    }

    private final class Node implements AutoCloseable {
        final NavigationRuntime runtime;
        final PubSubTransport transport;

        Node(String name, int port, NodeIdentity identity, List<String> subscriptions, List<PeerDescriptor> samples) {
            NodeConfig.NavigationSection config = new NodeConfig.NavigationSection();
            config.cycleIntervalMs = 150;
            config.staleAfterMs = 60_000;
            SubscriptionStore store = SubscriptionStore.loadOrCreate(dir.resolve(name + "-subscriptions.txt"));
            subscriptions.forEach(store::add);
            runtime = new NavigationRuntime(identity.nodeId().value(), "127.0.0.1", port, store, config,
                    () -> TOPICS, () -> samples);
            NodeEventService events = new NodeEventService(identity, dir.resolve(name + "-events"), topicId -> Optional.empty());
            transport = new PubSubTransport(new TransportConfig(name, "127.0.0.1", port, List.of(), 100, 500, 50, 100),
                    identity, new NodeTransportListener(null, runtime, null, events));
            runtime.attach(transport);
        }

        void start() throws Exception {
            transport.start();
            runtime.start();
        }

        @Override
        public void close() {
            runtime.close();
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
