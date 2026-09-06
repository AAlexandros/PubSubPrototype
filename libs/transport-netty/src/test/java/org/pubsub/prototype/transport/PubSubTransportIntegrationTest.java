package org.pubsub.prototype.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.EventPublisher;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.sampling.PeerDescriptor;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PubSubTransportIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void threeNodesReachFullMeshAndExchangePongs() throws Exception {
        int p1 = freePort();
        int p2 = freePort();
        int p3 = freePort();
        Probe l1 = new Probe();
        Probe l2 = new Probe();
        Probe l3 = new Probe();

        try (PubSubTransport n1 = node("node-1", p1, List.of(peer(p2), peer(p3)), identity("n1"), l1);
             PubSubTransport n2 = node("node-2", p2, List.of(peer(p1), peer(p3)), identity("n2"), l2);
             PubSubTransport n3 = node("node-3", p3, List.of(peer(p1), peer(p2)), identity("n3"), l3)) {
            n1.start();
            n2.start();
            n3.start();

            assertEventually(() -> n1.activePeerCount() == 2 && n2.activePeerCount() == 2 && n3.activePeerCount() == 2);
            assertEventually(() -> l1.pongs.size() >= 2 && l2.pongs.size() >= 2 && l3.pongs.size() >= 2);
        }
    }

    @Test
    void malformedFrameDoesNotCrashNode() throws Exception {
        int port = freePort();
        NodeIdentity identity = identity("malformed");

        try (PubSubTransport node = node("node-1", port, List.of(), identity, new Probe())) {
            node.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                byte[] badJson = "{not-json}".getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                output.write(ByteBuffer.allocate(4).putInt(badJson.length).array());
                output.write(badJson);
                output.flush();
            }
            TimeUnit.MILLISECONDS.sleep(250);
            assertEquals(0, node.activePeerCount());
        }
    }

    @Test
    void eventTransmitsBetweenNodes() throws Exception {
        int p1 = freePort();
        int p2 = freePort();
        Probe l1 = new Probe();
        Probe l2 = new Probe();
        NodeIdentity identity = identity("publisher");
        EventEnvelope event = new EventPublisher(identity.keyPair(), Clock.systemUTC(), tempDir.resolve("events"))
                .publish(new TopicId("b".repeat(64)), "hello".getBytes());

        try (PubSubTransport n1 = node("node-1", p1, List.of(peer(p2)), identity, l1);
             PubSubTransport n2 = node("node-2", p2, List.of(peer(p1)), identity("n2"), l2)) {
            n1.start();
            n2.start();

            assertEventually(() -> n1.activePeerCount() == 1 && n2.activePeerCount() == 1);
            n1.broadcastEvent(event);
            assertEventually(() -> l2.events.contains(event.eventId()));
        }
    }

    @Test
    void targetedEventConnectsDynamicallyAndDoesNotReachAnotherActivePeer() throws Exception {
        int p1 = freePort();
        int p2 = freePort();
        int p3 = freePort();
        Probe l1 = new Probe();
        Probe l2 = new Probe();
        Probe l3 = new Probe();
        NodeIdentity i1 = identity("target-publisher");
        NodeIdentity i2 = identity("target-recipient");
        NodeIdentity i3 = identity("target-bystander");
        EventEnvelope event = new EventPublisher(i1.keyPair(), Clock.systemUTC(), tempDir.resolve("target-events"))
                .publish(new TopicId("c".repeat(64)), "targeted".getBytes());

        try (PubSubTransport n1 = node("node-1", p1, List.of(peer(p3)), i1, l1);
             PubSubTransport n2 = node("node-2", p2, List.of(), i2, l2);
             PubSubTransport n3 = node("node-3", p3, List.of(peer(p1)), i3, l3)) {
            n1.start();
            n2.start();
            n3.start();
            assertEventually(() -> n1.activePeerCount() == 1 && n3.activePeerCount() == 1);

            n1.sendEvent(new PeerDescriptor(i2.nodeId().value(), "127.0.0.1", p2), event);

            assertEventually(() -> l2.events.contains(event.eventId()));
            TimeUnit.MILLISECONDS.sleep(250);
            assertTrue(!l3.events.contains(event.eventId()));
        }
    }

    private PubSubTransport node(String name, int port, List<PeerEndpoint> peers, NodeIdentity identity, Probe probe) {
        return new PubSubTransport(new TransportConfig(name, "127.0.0.1", port, peers, 100, 1000, 50, 200), identity, probe);
    }

    private NodeIdentity identity(String name) {
        return IdentityStore.loadOrCreate(tempDir.resolve(name));
    }

    private static PeerEndpoint peer(int port) {
        return new PeerEndpoint("127.0.0.1", port);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertEventually(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertTrue(check.ok());
    }

    private interface Check {
        boolean ok();
    }

    private static final class Probe implements TransportListener {
        private final Set<NodeId> pongs = ConcurrentHashMap.newKeySet();
        private final Set<String> events = ConcurrentHashMap.newKeySet();

        @Override
        public void pongReceived(NodeId nodeId, long rttMs) {
            pongs.add(nodeId);
        }

        @Override
        public void eventReceived(NodeId peerNodeId, EventEnvelope event) {
            events.add(event.eventId());
        }
    }
}
