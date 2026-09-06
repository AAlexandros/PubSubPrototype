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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisseminationIntegrationTest {
    private static final String TOPIC = "d".repeat(64);

    @TempDir
    Path dir;

    @Test
    void navigationLearnedCandidatesBecomeDisseminationPeersOverRealSockets() throws Exception {
        int portA = freePort();
        int portB = freePort();
        NodeIdentity identityA = IdentityStore.loadOrCreate(dir.resolve("a"));
        NodeIdentity identityB = IdentityStore.loadOrCreate(dir.resolve("b"));
        PeerDescriptor peerA = new PeerDescriptor(identityA.nodeId().value(), "127.0.0.1", portA);
        PeerDescriptor peerB = new PeerDescriptor(identityB.nodeId().value(), "127.0.0.1", portB);

        try (Node a = new Node("a", portA, identityA, peerB);
             Node b = new Node("b", portB, identityB, peerA)) {
            a.start();
            b.start();

            await(() -> a.dissemination.engine().view(TOPIC).orElseThrow().successor() != null
                    && b.dissemination.engine().view(TOPIC).orElseThrow().successor() != null);

            assertEquals(identityB.nodeId().value(),
                    a.dissemination.engine().view(TOPIC).orElseThrow().successor().nodeId());
            assertEquals(identityA.nodeId().value(),
                    b.dissemination.engine().view(TOPIC).orElseThrow().predecessor().nodeId());
            assertTrue(a.transport.activePeerCount() >= 1 && b.transport.activePeerCount() >= 1);
        }
    }

    private final class Node implements AutoCloseable {
        final NavigationRuntime navigation;
        final DisseminationRuntime dissemination;
        final PubSubTransport transport;

        Node(String name, int port, NodeIdentity identity, PeerDescriptor sample) {
            NodeConfig.NavigationSection navigationConfig = new NodeConfig.NavigationSection();
            navigationConfig.cycleIntervalMs = 100;
            navigationConfig.staleAfterMs = 60_000;
            SubscriptionStore store = SubscriptionStore.loadOrCreate(dir.resolve(name + "-subscriptions.txt"));
            store.add(TOPIC);
            navigation = new NavigationRuntime(identity.nodeId().value(), "127.0.0.1", port, store,
                    navigationConfig, () -> List.of(TOPIC), () -> List.of(sample));

            NodeConfig.DisseminationSection disseminationConfig = new NodeConfig.DisseminationSection();
            disseminationConfig.cycleIntervalMs = 100;
            disseminationConfig.staleAfterMs = 60_000;
            dissemination = new DisseminationRuntime(identity.nodeId().value(), "127.0.0.1", port,
                    disseminationConfig, navigation, () -> List.of(TOPIC));
            NodeEventService events = new NodeEventService(identity, dir.resolve(name + "-events"),
                    topicId -> Optional.empty());
            transport = new PubSubTransport(new TransportConfig(name, "127.0.0.1", port,
                    List.of(), 100, 500, 50, 100), identity,
                    new NodeTransportListener(null, navigation, dissemination, events));
            navigation.attach(transport);
            dissemination.attach(transport);
            events.attachTransport(transport);
            events.attachDissemination(dissemination);
        }

        void start() throws Exception {
            transport.start();
            navigation.start();
            dissemination.start();
        }

        @Override
        public void close() {
            transport.close();
            dissemination.close();
            navigation.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!check.getAsBoolean() && System.nanoTime() < end) TimeUnit.MILLISECONDS.sleep(10);
        assertTrue(check.getAsBoolean());
    }
}
