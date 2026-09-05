package org.pubsub.prototype.securecyclon;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.sampling.PeerDescriptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecureCyclonTest {
    private static final PeerDescriptor A = peer("a"), B = peer("b"), C = peer("c"), D = peer("d");
    private static PeerDescriptor peer(String id) { return new PeerDescriptor(id.repeat(64), "localhost", 7000); }
    private static SecureCyclon node(PeerDescriptor self) {
        return new SecureCyclon(self, 3, 2, 100, 10, new Random(0), ignored -> {});
    }
    private static Exchange request(PeerDescriptor sender, PeerDescriptor receiver, long now) {
        return new Exchange(List.of(SecureLink.fresh(sender, now).transfer(receiver.nodeId())), List.of());
    }

    @Test void matchesDocumentedPeerNetTraceFixture() throws Exception {
        Properties fixture = new Properties();
        try (var input = getClass().getResourceAsStream("/peernet-non-tit-for-tat.properties")) { fixture.load(input); }
        var a = new SecureCyclon(A, 3, 2, 100, 10, new Random(Long.parseLong(fixture.getProperty("seed"))), ignored -> {});
        Arrays.stream(fixture.getProperty("initial").split(",")).map(SecureCyclonTest::peer).forEach(a::bootstrap);
        var out = a.cycle(1000).orElseThrow();
        assertEquals(peer(fixture.getProperty("selected")), out.peer());
        assertEquals(Arrays.stream(fixture.getProperty("request").split(",")).map(SecureCyclonTest::peer).toList(),
                out.exchange().links().stream().map(SecureLink::peer).toList());
        a.response(out.peer().nodeId(), out.requestId(), new Exchange(List.of(), List.of()), 1001);
        assertEquals(Arrays.stream(fixture.getProperty("result").split(",")).map(SecureCyclonTest::peer).toList(), a.view());
        assertEquals(List.of(peer(fixture.getProperty("nonSwappable"))), a.links().stream().filter(p -> !p.swappable()).map(SecureLink::peer).toList());
    }

    @Test void returningOwnedLinkRestoresSwapPermissionWithoutDuplicateNode() {
        var a = node(A); a.bootstrap(B); a.bootstrap(C);
        var out = a.cycle(1000).orElseThrow();
        var transferred = out.exchange().links().stream().filter(p -> p.peer().equals(B)).findFirst().orElseThrow();
        a.response(C.nodeId(), out.requestId(), new Exchange(List.of(), List.of()), 1001);
        assertFalse(a.links().getFirst().swappable());
        a.request(C.nodeId(), new Exchange(List.of(SecureLink.fresh(C, 1100).transfer(A.nodeId()),
                transferred.transfer(A.nodeId())), List.of()), 1100);
        assertEquals(1, a.links().stream().filter(p -> p.peer().equals(B) && p.swappable()).count());
        assertEquals(2, a.view().size());
    }

    @Test void observationHistoryExpiresWithoutTreatingSamplesAsOwnedLinks() {
        var a = node(A);
        var observed = SecureLink.fresh(D, 1000).transfer(B.nodeId());
        a.request(B.nodeId(), new Exchange(request(B, A, 1000).links(), List.of(observed)), 1000);
        assertEquals(List.of(B), a.view()); // D is evidence, not an owned peer.
        a.cycle(2401); // (capacity 3 + threshold 10) * interval 100 = 1300 ms.
        a.request(C.nodeId(), new Exchange(request(C, A, 2401).links(),
                List.of(SecureLink.fresh(D, 1000).transfer(C.nodeId()))), 2401);
        assertTrue(a.drainReports().isEmpty()); // The expired observation cannot generate a fork report.
        assertEquals(List.of(C), a.view());
    }

    @Test void passiveThreadDoesNotResendLinksLockedByActiveExchange() {
        var a = node(A); a.bootstrap(B); a.bootstrap(C); a.bootstrap(D);
        var active = a.cycle(1000).orElseThrow();
        var locked = active.exchange().links().get(1).peer();
        var e = peer("e");
        var response = a.request(e.nodeId(), request(e, A, 1001), 1001);
        assertEquals(1, response.links().size());
        assertFalse(response.links().stream().anyMatch(p -> p.peer().equals(locked)));
        assertTrue(a.view().size() <= 3);
    }

    @Test void referenceOldestSelectionAndExchangeContents() {
        SecureCyclon a = node(A);
        a.bootstrap(B); a.bootstrap(C); a.bootstrap(D);
        var out = a.cycle(1000).orElseThrow();
        assertEquals(D, out.peer()); // reverse stable sort + pollLast, as in PeerNet
        assertEquals(2, out.exchange().links().size());
        assertEquals(A, out.exchange().links().getFirst().peer());
        assertEquals(List.of(A.nodeId(), D.nodeId()), out.exchange().links().getFirst().owners());
        assertEquals(1000, out.exchange().links().getFirst().timestamp());
        assertTrue(out.exchange().samples().stream().anyMatch(p -> p.peer().equals(D)));
        assertFalse(a.view().contains(D));
        assertTrue(a.cycle(1050).isEmpty());
    }

    @Test void referenceRetainsTransferredLinksAsNonSwappableAndRedeemsOldest() {
        SecureCyclon a = node(A);
        a.bootstrap(B); a.bootstrap(C);
        var out = a.cycle(1000).orElseThrow();
        a.response(C.nodeId(), out.requestId(), new Exchange(List.of(), List.of()), 1001);
        assertEquals(List.of(B), a.view());
        assertFalse(a.links().getFirst().swappable());
        assertEquals(B, a.cycle(1100).orElseThrow().peer());
    }

    @Test void passiveExchangeUsesFreeSlotsThenSentLinksThenNonSwappableLinks() {
        SecureCyclon a = node(A);
        a.bootstrap(B); a.bootstrap(C); a.bootstrap(D);
        Exchange reply = a.request(B.nodeId(), request(B, A, 1000), 1000);
        assertEquals(2, reply.links().size());
        assertTrue(reply.links().stream().noneMatch(p -> p.peer().equals(B)));
        assertEquals(3, a.view().size());
        assertEquals(2, a.links().stream().filter(p -> !p.swappable()).count());
        assertEquals(1000, a.links().stream().filter(p -> p.peer().equals(B)).findFirst().orElseThrow().timestamp());
    }

    @Test void invalidExchangesAreAtomic() {
        SecureCyclon a = node(A); a.bootstrap(C);
        SecureLink valid = request(B, A, 1000).links().getFirst();
        List<Exchange> invalid = List.of(
                new Exchange(List.of(new SecureLink(null, 1000, "bad", List.of(), true)), List.of()),
                new Exchange(List.of(), List.of()),
                new Exchange(List.of(valid, valid), List.of()),
                new Exchange(List.of(valid, SecureLink.fresh(A, 1000).transfer(B.nodeId()).transfer(A.nodeId())), List.of()),
                new Exchange(List.of(valid.retained()), List.of()),
                new Exchange(List.of(new SecureLink(B, 1000, "bad", valid.owners(), true)), List.of()),
                new Exchange(List.of(SecureLink.fresh(B, 1000).transfer(C.nodeId())), List.of()),
                new Exchange(List.of(valid, SecureLink.fresh(D, 1000).transfer(A.nodeId())), List.of()),
                new Exchange(List.of(SecureLink.fresh(B, 2000).transfer(A.nodeId())), List.of()));
        var before = a.links();
        for (Exchange exchange : invalid) {
            assertThrows(IllegalArgumentException.class, () -> a.request(B.nodeId(), exchange, 1000));
            assertEquals(before, a.links());
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
        assertEquals(1, frequency.drainReports().size());
        var fork = SecureLink.fresh(B, 1000).transfer(C.nodeId());
        assertThrows(IllegalArgumentException.class, () -> a.request(C.nodeId(),
                new Exchange(request(C, A, 1100).links(), List.of(fork)), 1100));
        assertTrue(a.view().isEmpty());
        var proof = a.drainReports().getFirst();
        SecureCyclon receiver = node(D); receiver.bootstrap(B);
        receiver.acceptReport(proof, 1100);
        assertFalse(receiver.view().contains(B));
        assertThrows(IllegalArgumentException.class, () -> receiver.acceptReport(new LinkProof(proof.first(), proof.first()), 1100));
    }

    @Test void rejectsUnsolicitedResponseAndBootstrapCannotPinSeeds() {
        SecureCyclon a = node(A); a.bootstrap(B); a.bootstrap(B); a.bootstrap(A);
        assertEquals(List.of(B), a.view());
        assertThrows(IllegalArgumentException.class, () -> a.response(B.nodeId(), UUID.randomUUID(), new Exchange(List.of(), List.of()), 1000));
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
                assertEquals(n.view().size(), n.view().stream().map(PeerDescriptor::nodeId).distinct().count());
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
            var target = nodes.get(out.peer().nodeId());
            if (target != null) n.response(out.peer().nodeId(), out.requestId(), target.request(id, out.exchange(), time), time);
        }));
    }
}
