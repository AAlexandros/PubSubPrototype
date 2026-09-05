package org.pubsub.prototype.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.protocol.*;
import org.pubsub.prototype.sampling.PeerDescriptor;
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
                await(() -> b.runtime.sampling().view().contains(c.peer) || c.runtime.sampling().view().contains(b.peer));
                // The missing relationship becomes a real HELLO connection in a subsequent gossip cycle.
                await(() -> b.transport.activePeerCount() == 2 && c.transport.activePeerCount() == 2);
                for (Node n : List.of(a, b, c)) {
                    var view = n.runtime.sampling().view();
                    assertTrue(view.size() <= 2);
                    assertFalse(view.contains(n.peer));
                    assertEquals(view.size(), new HashSet<>(view).size());
                }
            } finally { c.close(); }
            // Require absence throughout multiple cycles rather than a single transient view snapshot.
            await(() -> !a.runtime.sampling().view().contains(c.peer) && !b.runtime.sampling().view().contains(c.peer));
            TimeUnit.MILLISECONDS.sleep(800);
            assertFalse(a.runtime.sampling().view().contains(c.peer));
            assertFalse(b.runtime.sampling().view().contains(c.peer));
            try (Node restarted = new Node("c", p3, List.of(new PeerEndpoint("127.0.0.1", p1)))) {
                assertEquals(c.peer, restarted.peer);
                restarted.start();
                await(() -> a.runtime.sampling().view().contains(restarted.peer) || b.runtime.sampling().view().contains(restarted.peer));
            }
        }
    }

    @Test void wireInvalidExchangeCannotMutateViewAndSimultaneousConnectionsConverge() throws Exception {
        int p1 = port(), p2 = port();
        try (Node a = new Node("a", p1, List.of()); Node b = new Node("b", p2, List.of())) {
            a.transport.start(); b.transport.start(); // No scheduler: compare exact view before/after.
            var before = a.runtime.sampling().links();
            var bad = new Exchange(List.of(), List.of());
            a.transport.sendSampling(b.peer, ProtocolMessage.gossip(MessageType.SECURECYCLON_REQUEST, UUID.randomUUID(), bad));
            b.transport.sendSampling(a.peer, ProtocolMessage.gossip(MessageType.SECURECYCLON_REQUEST, UUID.randomUUID(), bad));
            await(() -> a.transport.activePeerCount() == 1 && b.transport.activePeerCount() == 1);
            TimeUnit.MILLISECONDS.sleep(250);
            assertEquals(before, a.runtime.sampling().links());
            try (Socket socket = new Socket("127.0.0.1", p1)) {
                byte[] payload = "{broken-json".getBytes();
                socket.getOutputStream().write(ByteBuffer.allocate(payload.length + 4).putInt(payload.length).put(payload).array());
            }
            TimeUnit.MILLISECONDS.sleep(100);
            assertEquals(before, a.runtime.sampling().links());
            assertEquals(1, a.transport.activePeerCount());
        }
    }

    private final class Node implements AutoCloseable {
        final PeerDescriptor peer;
        final PeerSamplingRuntime runtime;
        final PubSubTransport transport;
        Node(String name, int port, List<PeerEndpoint> seeds) {
            var identity = IdentityStore.loadOrCreate(dir.resolve(name));
            peer = new PeerDescriptor(identity.nodeId().value(), "127.0.0.1", port);
            var config = new NodeConfig.SamplingSection(); config.cycleIntervalMs = 150; config.randomSeed = name.hashCode();
            runtime = new PeerSamplingRuntime(peer, config, new TransportListener() {});
            transport = new PubSubTransport(new TransportConfig(name, "127.0.0.1", port, seeds, 100, 500, 50, 100), identity, runtime);
            runtime.attach(transport);
        }
        void start() throws Exception { transport.start(); runtime.start(); }
        @Override public void close() { runtime.close(); transport.close(); }
    }
    private static int port() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }
    private static void await(BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!check.getAsBoolean() && System.nanoTime() < end) TimeUnit.MILLISECONDS.sleep(10);
        assertTrue(check.getAsBoolean());
    }
}
