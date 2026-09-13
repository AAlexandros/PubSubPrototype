package org.pubsub.prototype.securecyclon;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.sampling.NodeEndpoint;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecureCyclonTest {
    private static final NodeEndpoint A = peer("a"), B = peer("b"), C = peer("c"), D = peer("d");
    private static NodeEndpoint peer(String id) { return new NodeEndpoint(id.repeat(64), "localhost", 7000); }
    private static SecureCyclon node(NodeEndpoint self) {
        return new SecureCyclon(self, 3, 2, 100, 10, new Random(0), ignored -> {});
    }
    private static GossipExchange request(NodeEndpoint sender, NodeEndpoint receiver, long now) {
        return new GossipExchange(List.of(NodeDescriptor.fresh(sender, now).transfer(receiver.nodeId())), List.of());
    }

    @Test void matchesDocumentedPeerNetTraceFixture() throws Exception {
        Properties fixture = new Properties();
        try (var input = getClass().getResourceAsStream("/peernet-non-tit-for-tat.properties")) { fixture.load(input); }
        var a = new SecureCyclon(A, 3, 2, 100, 10, new Random(Long.parseLong(fixture.getProperty("seed"))), ignored -> {});
        Arrays.stream(fixture.getProperty("initial").split(",")).map(SecureCyclonTest::peer).forEach(a::bootstrap);
        var out = a.cycle(1000).orElseThrow();
        assertEquals(peer(fixture.getProperty("selected")), out.partner());
        assertEquals(Arrays.stream(fixture.getProperty("request").split(",")).map(SecureCyclonTest::peer).toList(),
                out.exchange().descriptors().stream().map(NodeDescriptor::creator).toList());
        a.response(out.partner().nodeId(), out.requestId(), new GossipExchange(List.of(), List.of()), 1001);
        assertEquals(Arrays.stream(fixture.getProperty("result").split(",")).map(SecureCyclonTest::peer).toList(), a.view());
        assertEquals(List.of(peer(fixture.getProperty("nonSwappable"))), a.descriptors().stream().filter(p -> !p.swappable()).map(NodeDescriptor::creator).toList());
    }

    @Test void returningOwnedLinkRestoresSwapPermissionWithoutDuplicateNode() {
        var a = node(A); a.bootstrap(B); a.bootstrap(C);
        var out = a.cycle(1000).orElseThrow();
        var transferred = out.exchange().descriptors().stream().filter(p -> p.creator().equals(B)).findFirst().orElseThrow();
        a.response(C.nodeId(), out.requestId(), new GossipExchange(List.of(), List.of()), 1001);
        assertFalse(a.descriptors().getFirst().swappable());
        a.request(C.nodeId(), new GossipExchange(List.of(NodeDescriptor.fresh(C, 1100).transfer(A.nodeId()),
                transferred.transfer(A.nodeId())), List.of()), 1100);
        assertEquals(1, a.descriptors().stream().filter(p -> p.creator().equals(B) && p.swappable()).count());
        assertEquals(2, a.view().size());
    }

    @Test void observationHistoryExpiresWithoutTreatingSamplesAsOwnedLinks() {
        var a = node(A);
        var observed = NodeDescriptor.fresh(D, 1000).transfer(B.nodeId());
        a.request(B.nodeId(), new GossipExchange(request(B, A, 1000).descriptors(), List.of(observed)), 1000);
        assertEquals(List.of(B), a.view()); // D is evidence, not an owned peer.
        a.cycle(2401); // (capacity 3 + threshold 10) * interval 100 = 1300 ms.
        a.request(C.nodeId(), new GossipExchange(request(C, A, 2401).descriptors(),
                List.of(NodeDescriptor.fresh(D, 1000).transfer(C.nodeId()))), 2401);
        assertTrue(a.drainProofs().isEmpty()); // The expired observation cannot generate a fork proof.
        assertEquals(List.of(C), a.view());
    }

    @Test void passiveThreadDoesNotResendLinksLockedByActiveExchange() {
        var a = node(A); a.bootstrap(B); a.bootstrap(C); a.bootstrap(D);
        var active = a.cycle(1000).orElseThrow();
        var locked = active.exchange().descriptors().get(1).creator();
        var e = peer("e");
        var response = a.request(e.nodeId(), request(e, A, 1001), 1001);
        assertEquals(1, response.descriptors().size());
        assertFalse(response.descriptors().stream().anyMatch(p -> p.creator().equals(locked)));
        assertTrue(a.view().size() <= 3);
    }

    @Test void referenceOldestSelectionAndExchangeContents() {
        SecureCyclon a = node(A);
        a.bootstrap(B); a.bootstrap(C); a.bootstrap(D);
        var out = a.cycle(1000).orElseThrow();
        assertEquals(D, out.partner()); // reverse stable sort + pollLast, as in PeerNet
        assertEquals(2, out.exchange().descriptors().size());
        assertEquals(A, out.exchange().descriptors().getFirst().creator());
        assertEquals(List.of(A.nodeId(), D.nodeId()), out.exchange().descriptors().getFirst().ownershipChain());
        assertEquals(1000, out.exchange().descriptors().getFirst().timestamp());
        assertTrue(out.exchange().samples().stream().anyMatch(p -> p.creator().equals(D)));
        assertFalse(a.view().contains(D));
        assertTrue(a.cycle(1050).isEmpty());
    }

    @Test void referenceRetainsTransferredLinksAsNonSwappableAndRedeemsOldest() {
        SecureCyclon a = node(A);
        a.bootstrap(B); a.bootstrap(C);
        var out = a.cycle(1000).orElseThrow();
        a.response(C.nodeId(), out.requestId(), new GossipExchange(List.of(), List.of()), 1001);
        assertEquals(List.of(B), a.view());
        assertFalse(a.descriptors().getFirst().swappable());
        assertEquals(B, a.cycle(1100).orElseThrow().partner());
    }

    @Test void passiveExchangeUsesFreeSlotsThenSentLinksThenNonSwappableLinks() {
        SecureCyclon a = node(A);
        a.bootstrap(B); a.bootstrap(C); a.bootstrap(D);
        GossipExchange reply = a.request(B.nodeId(), request(B, A, 1000), 1000);
        assertEquals(2, reply.descriptors().size());
        assertTrue(reply.descriptors().stream().noneMatch(p -> p.creator().equals(B)));
        assertEquals(3, a.view().size());
        assertEquals(2, a.descriptors().stream().filter(p -> !p.swappable()).count());
        assertEquals(1000, a.descriptors().stream().filter(p -> p.creator().equals(B)).findFirst().orElseThrow().timestamp());
    }

    @Test void invalidExchangesAreAtomic() {
        SecureCyclon a = node(A); a.bootstrap(C);
        NodeDescriptor valid = request(B, A, 1000).descriptors().getFirst();
        List<GossipExchange> invalid = List.of(
                new GossipExchange(List.of(new NodeDescriptor(null, 1000, "bad", List.of(), true)), List.of()),
                new GossipExchange(List.of(), List.of()),
                new GossipExchange(List.of(valid, valid), List.of()),
                new GossipExchange(List.of(valid, NodeDescriptor.fresh(A, 1000).transfer(B.nodeId()).transfer(A.nodeId())), List.of()),
                new GossipExchange(List.of(valid.retained()), List.of()),
                new GossipExchange(List.of(new NodeDescriptor(B, 1000, "bad", valid.ownershipChain(), true)), List.of()),
                new GossipExchange(List.of(NodeDescriptor.fresh(B, 1000).transfer(C.nodeId())), List.of()),
                new GossipExchange(List.of(valid, NodeDescriptor.fresh(D, 1000).transfer(A.nodeId())), List.of()),
                new GossipExchange(List.of(NodeDescriptor.fresh(B, 2000).transfer(A.nodeId())), List.of()));
        var before = a.descriptors();
        for (GossipExchange exchange : invalid) {
            assertThrows(IllegalArgumentException.class, () -> a.request(B.nodeId(), exchange, 1000));
            assertEquals(before, a.descriptors());
        }
    }

    @Test void enforcesCreationFrequencyReplayAndOwnershipConsistency() {
        SecureCyclon a = node(A);
        a.request(B.nodeId(), request(B, A, 1000), 1000);
        assertThrows(IllegalArgumentException.class, () -> a.request(B.nodeId(), request(B, A, 1000), 1001));
        SecureCyclon frequency = node(A);
        frequency.request(B.nodeId(), request(B, A, 1000), 1000);
        assertThrows(IllegalArgumentException.class, () -> frequency.request(B.nodeId(), request(B, A, 1050), 1050));
        assertTrue(frequency.view().isEmpty());
        assertEquals(1, frequency.drainProofs().size());
        var fork = NodeDescriptor.fresh(B, 1000).transfer(C.nodeId());
        assertThrows(IllegalArgumentException.class, () -> a.request(C.nodeId(),
                new GossipExchange(request(C, A, 1100).descriptors(), List.of(fork)), 1100));
        assertTrue(a.view().isEmpty());
        var proof = a.drainProofs().getFirst();
        SecureCyclon receiver = node(D); receiver.bootstrap(B);
        receiver.acceptProof(proof, 1100);
        assertFalse(receiver.view().contains(B));
        assertThrows(IllegalArgumentException.class, () -> receiver.acceptProof(new ViolationProof(proof.first(), proof.first()), 1100));
    }

    @Test void rejectsUnsolicitedResponseAndBootstrapCannotPinSeeds() {
        SecureCyclon a = node(A); a.bootstrap(B); a.bootstrap(B); a.bootstrap(A);
        assertEquals(List.of(B), a.view());
        assertThrows(IllegalArgumentException.class, () -> a.response(B.nodeId(), UUID.randomUUID(), new GossipExchange(List.of(), List.of()), 1000));
        a.cycle(1000);
        a.bootstrap(B);
        assertTrue(a.view().isEmpty());
    }

    @Test void deterministicAsymmetricNetworkDiscoversRemovesAndRediscovers() {
        var descriptors = List.of(A, B, C);
        Map<String, SecureCyclon> nodes = new LinkedHashMap<>();
        descriptors.forEach(p -> nodes.put(p.nodeId(), node(p)));
        nodes.get(B.nodeId()).bootstrap(A); nodes.get(C.nodeId()).bootstrap(A);
        boolean discovered = false;
        for (long time = 1000; time < 5000; time += 100) {
            tick(nodes, time);
            discovered |= nodes.get(B.nodeId()).view().contains(C) || nodes.get(C.nodeId()).view().contains(B);
            nodes.forEach((id, n) -> {
                assertTrue(n.view().size() <= 3);
                assertEquals(n.view().size(), n.view().stream().map(NodeEndpoint::nodeId).distinct().count());
                assertTrue(n.view().stream().noneMatch(p -> p.nodeId().equals(id)));
            });
        }
        assertTrue(discovered);
        nodes.remove(C.nodeId());
        for (long time = 5000; time < 9000; time += 100) tick(nodes, time);
        nodes.values().forEach(n -> assertFalse(n.view().contains(C)));
        var restarted = node(C); restarted.bootstrap(A); nodes.put(C.nodeId(), restarted);
        boolean rediscovered = false;
        for (long time = 9000; time < 13000; time += 100) {
            tick(nodes, time);
            rediscovered |= nodes.get(A.nodeId()).view().contains(C) || nodes.get(B.nodeId()).view().contains(C);
        }
        assertTrue(rediscovered);
    }
    private static void tick(Map<String, SecureCyclon> nodes, long time) {
        nodes.forEach((id, n) -> n.cycle(time).ifPresent(out -> {
            var target = nodes.get(out.partner().nodeId());
            if (target != null) n.response(out.partner().nodeId(), out.requestId(), target.request(id, out.exchange(), time), time);
        }));
    }
}
