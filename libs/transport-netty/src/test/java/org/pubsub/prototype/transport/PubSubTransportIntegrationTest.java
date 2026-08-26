package org.pubsub.prototype.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.protocol.IdentityStore;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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

        @Override
        public void pongReceived(NodeId nodeId, long rttMs) {
            pongs.add(nodeId);
        }
    }
}
