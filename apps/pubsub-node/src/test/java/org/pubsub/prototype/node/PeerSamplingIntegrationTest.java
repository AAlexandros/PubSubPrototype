package org.pubsub.prototype.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.node.config.NodeConfig;
import org.pubsub.prototype.node.service.PeerSamplingProtocolService;
import org.pubsub.prototype.protocol.*;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.securecyclon.*;
import org.pubsub.prototype.transport.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class PeerSamplingIntegrationTest {
    @TempDir Path dir;
    @Test void gossipLearnsRealEndpointConnectsAndSurvivesFailureAndRestart() throws Exception {
        int p1 = port(), p2 = port(), p3 = port();
        try (Node a = new Node("a", p1, List.of());
             Node b = new Node("b", p2, List.of(new PeerEndpoint("127.0.0.1", p1)))) {
            Node c = new Node("c", p3, List.of(new PeerEndpoint("127.0.0.1", p1)));
            try {
                a.start(); b.start(); c.start();
                await(() -> b.service.sampling().view().contains(c.peer) || c.service.sampling().view().contains(b.peer));
                // The missing relationship becomes a real HELLO connection in a subsequent gossip cycle.
                await(() -> b.transport.activePeerCount() == 2 && c.transport.activePeerCount() == 2);
                for (Node n : List.of(a, b, c)) {
                    var view = n.service.sampling().view();
                    assertTrue(view.size() <= 2);
                    assertFalse(view.contains(n.peer));
                }
            } finally { c.close(); }
            // Require absence throughout multiple cycles rather than a single transient view snapshot.
            await(() -> !a.service.sampling().view().contains(c.peer) && !b.service.sampling().view().contains(c.peer));
            TimeUnit.MILLISECONDS.sleep(800);
            assertFalse(a.service.sampling().view().contains(c.peer));
            assertFalse(b.service.sampling().view().contains(c.peer));
            try (Node restarted = new Node("c", p3, List.of(new PeerEndpoint("127.0.0.1", p1)))) {
                assertEquals(c.peer, restarted.peer);
                restarted.start();
                await(() -> a.service.sampling().view().contains(restarted.peer) || b.service.sampling().view().contains(restarted.peer));
            }
        }
    }

    @Test void wireInvalidExchangeCannotMutateViewAndSimultaneousConnectionsConverge() throws Exception {
        int p1 = port(), p2 = port();
        try (Node a = new Node("a", p1, List.of()); Node b = new Node("b", p2, List.of())) {
            a.transport.start(); b.transport.start(); // No scheduler: compare exact view before/after.
            var before = a.service.sampling().descriptors();
            var bad = new GossipExchange(List.of(), List.of());
            a.transport.sendMessage(b.peer, ProtocolMessage.gossip(MessageType.SECURECYCLON_REQUEST, UUID.randomUUID(), bad));
            b.transport.sendMessage(a.peer, ProtocolMessage.gossip(MessageType.SECURECYCLON_REQUEST, UUID.randomUUID(), bad));
            await(() -> a.transport.activePeerCount() == 1 && b.transport.activePeerCount() == 1);
            TimeUnit.MILLISECONDS.sleep(250);
            assertEquals(before, a.service.sampling().descriptors());
            try (Socket socket = new Socket("127.0.0.1", p1)) {
                byte[] payload = "{broken-json".getBytes();
                socket.getOutputStream().write(ByteBuffer.allocate(payload.length + 4).putInt(payload.length).put(payload).array());
            }
            TimeUnit.MILLISECONDS.sleep(100);
            assertEquals(before, a.service.sampling().descriptors());
            assertEquals(1, a.transport.activePeerCount());
        }
    }

    private final class Node implements AutoCloseable {
        final NodeEndpoint peer;
        final PeerSamplingProtocolService service;
        final PubSubTransport transport;
        Node(String name, int port, List<PeerEndpoint> seeds) {
            var identity = IdentityStore.loadOrCreate(dir.resolve(name));
            peer = new NodeEndpoint(identity.nodeId().value(), "127.0.0.1", port);
            NodeConfig.SamplingSection config = new NodeConfig.SamplingSection();
            config.viewSize = 2;
            config.swapLength = 2;
            config.cycleIntervalMs = 150L;
            config.ageThreshold = 10;
            config.proofFanout = 2;
            config.randomSeed = (long) name.hashCode();
            service = new PeerSamplingProtocolService(peer, config);
            TransportListener listener = new TransportListener() {
                @Override
                public void seedResolved(NodeEndpoint peer) {
                    service.seedResolved(peer);
                }

                @Override
                public void samplingReceived(NodeId peer, ProtocolMessage message) {
                    service.messageReceived(peer, message);
                }

                @Override
                public void peerDisconnected(NodeId peer) {
                    service.peerDisconnected(peer);
                }

                @Override
                public void peerConnected(NodeId peer) {
                    service.peerConnected(peer);
                }
            };
            transport = new PubSubTransport(
                    new TransportConfig(name, "127.0.0.1", port, seeds, 100, 500, 50, 100),
                    identity, peer, listener);
            service.attach(transport);
        }
        void start() throws Exception { transport.start(); service.start(); }
        @Override public void close() { service.close(); transport.close(); }
    }
    private static int port() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }
    private static void await(BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!check.getAsBoolean() && System.nanoTime() < end) TimeUnit.MILLISECONDS.sleep(10);
        assertTrue(check.getAsBoolean());
    }
}
