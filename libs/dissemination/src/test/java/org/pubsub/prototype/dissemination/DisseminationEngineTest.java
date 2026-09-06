package org.pubsub.prototype.dissemination;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.navigation.NavigationPeerDescriptor;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisseminationEngineTest {
    private static final String TOPIC = "a".repeat(64);
    private static final String OTHER_TOPIC = "b".repeat(64);

    @Test
    void selectsPredecessorSuccessorWraparoundAndOnePeer() {
        DisseminationEngine middle = engine("5", 1, 0);
        middle.subscribe(TOPIC, List.of(nav("2", TOPIC), nav("8", TOPIC)), 100);
        assertEquals(id("2"), middle.view(TOPIC).orElseThrow().predecessor().nodeId());
        assertEquals(id("8"), middle.view(TOPIC).orElseThrow().successor().nodeId());

        DisseminationEngine wrapped = engine("1", 1, 0);
        wrapped.subscribe(TOPIC, List.of(nav("2", TOPIC), nav("f", TOPIC)), 100);
        assertEquals(id("f"), wrapped.view(TOPIC).orElseThrow().predecessor().nodeId());
        assertEquals(id("2"), wrapped.view(TOPIC).orElseThrow().successor().nodeId());

        DisseminationEngine one = engine("5", 1, 0);
        one.subscribe(TOPIC, List.of(nav("7", TOPIC), nav("5", TOPIC), nav("7", TOPIC)), 100);
        assertEquals(id("7"), one.view(TOPIC).orElseThrow().predecessor().nodeId());
        assertEquals(id("7"), one.view(TOPIC).orElseThrow().successor().nodeId());

        DisseminationEngine solo = engine("5", 1, 0);
        solo.subscribe(TOPIC, List.of(nav("5", TOPIC)), 100);
        assertNull(solo.view(TOPIC).orElseThrow().predecessor());
        assertNull(solo.view(TOPIC).orElseThrow().successor());
    }

    @Test
    void receivedAndNavigationCandidatesImproveNeighborsWhileUnrelatedTopicsAreRejected() {
        DisseminationEngine local = engine("5", 1, 0);
        local.subscribe(TOPIC, List.of(nav("2", TOPIC), nav("8", TOPIC), nav("4", OTHER_TOPIC)), 100);
        DisseminationExchange incoming = new DisseminationExchange(TOPIC, peer("3", TOPIC),
                List.of(peer("4", TOPIC), peer("6", TOPIC)), DisseminationEngine.PROTOCOL_VERSION);

        local.request(id("3"), incoming, List.of(nav("4", OTHER_TOPIC)), 200);

        TopicDisseminationView view = local.view(TOPIC).orElseThrow();
        assertEquals(id("4"), view.predecessor().nodeId());
        assertEquals(id("6"), view.successor().nodeId());
        assertTrue(view.randomPeers().stream().noneMatch(p -> p.nodeId().equals(id("4"))));
        assertThrows(IllegalArgumentException.class, () -> new DisseminationExchange(TOPIC,
                peer("3", TOPIC), List.of(peer("4", OTHER_TOPIC)), DisseminationEngine.PROTOCOL_VERSION));
    }

    @Test
    void randomLinksAreSameTopicBoundedAndReproducibleWithoutReplacingRing() {
        List<NavigationPeerDescriptor> candidates = List.of(
                nav("2", TOPIC), nav("4", TOPIC), nav("6", TOPIC), nav("8", TOPIC), nav("9", OTHER_TOPIC));
        DisseminationEngine first = engine("5", 2, 42);
        DisseminationEngine second = engine("5", 2, 42);

        first.subscribe(TOPIC, candidates, 100);
        second.subscribe(TOPIC, candidates, 100);

        TopicDisseminationView a = first.view(TOPIC).orElseThrow();
        TopicDisseminationView b = second.view(TOPIC).orElseThrow();
        assertEquals(2, a.randomPeers().size());
        assertEquals(a.randomPeers(), b.randomPeers());
        assertTrue(a.randomPeers().stream().allMatch(peer -> peer.subscribedTopicIds().contains(TOPIC)));
        assertEquals(id("4"), a.predecessor().nodeId());
        assertEquals(id("6"), a.successor().nodeId());
    }

    @Test
    void forwardingFollowsLocalRingAndRandomSourceRulesWithoutEcho() {
        DisseminationEngine engine = engine("5", 4, 7);
        engine.subscribe(TOPIC, List.of(nav("2", TOPIC), nav("4", TOPIC), nav("6", TOPIC), nav("8", TOPIC)), 100);
        TopicDisseminationView view = engine.view(TOPIC).orElseThrow();

        List<String> local = ids(engine.forwardingTargets(TOPIC, null));
        assertTrue(local.containsAll(List.of(id("4"), id("6"))));
        assertEquals(4, local.size());

        List<String> fromPredecessor = ids(engine.forwardingTargets(TOPIC, id("4")));
        assertTrue(fromPredecessor.contains(id("6")));
        assertTrue(!fromPredecessor.contains(id("4")));

        String randomOnly = view.randomPeers().stream()
                .map(DisseminationPeerDescriptor::nodeId)
                .filter(candidate -> !candidate.equals(id("4")) && !candidate.equals(id("6")))
                .findFirst().orElseThrow();
        List<String> fromRandom = ids(engine.forwardingTargets(TOPIC, randomOnly));
        assertEquals(List.of(id("4"), id("6")), fromRandom);
        assertTrue(engine.forwardingTargets(OTHER_TOPIC, null).isEmpty());
    }

    @Test
    void topicsMaintainIndependentViewsAndUnsubscribeRemovesOnlyOne() {
        DisseminationEngine engine = engine("5", 1, 11);
        engine.subscribe(TOPIC, List.of(nav("2", TOPIC)), 100);
        engine.subscribe(OTHER_TOPIC, List.of(nav("8", OTHER_TOPIC)), 100);

        assertEquals(id("2"), engine.view(TOPIC).orElseThrow().successor().nodeId());
        assertEquals(id("8"), engine.view(OTHER_TOPIC).orElseThrow().successor().nodeId());

        engine.unsubscribe(TOPIC);
        assertTrue(engine.view(TOPIC).isEmpty());
        assertTrue(engine.view(OTHER_TOPIC).isPresent());
    }

    @Test
    void peerFailureRepairsFromRemainingCandidates() {
        DisseminationEngine engine = engine("5", 1, 0);
        engine.subscribe(TOPIC, List.of(nav("2", TOPIC), nav("4", TOPIC), nav("6", TOPIC), nav("8", TOPIC)), 100);
        engine.peerUnavailable(id("4"));
        assertEquals(id("2"), engine.view(TOPIC).orElseThrow().predecessor().nodeId());
        engine.peerUnavailable(id("6"));
        assertEquals(id("8"), engine.view(TOPIC).orElseThrow().successor().nodeId());

        // Indirect Navigation knowledge cannot immediately resurrect an unavailable peer.
        engine.cycle(200, ignored -> List.of(nav("4", TOPIC), nav("6", TOPIC)));
        assertEquals(id("2"), engine.view(TOPIC).orElseThrow().predecessor().nodeId());
        assertEquals(id("8"), engine.view(TOPIC).orElseThrow().successor().nodeId());
        assertTrue(engine.view(TOPIC).orElseThrow().randomPeers().stream()
                .noneMatch(peer -> peer.nodeId().equals(id("4")) || peer.nodeId().equals(id("6"))));

        // A direct gossip exchange proves that the same persistent identity has restarted.
        DisseminationExchange restarted = new DisseminationExchange(TOPIC, peer("4", TOPIC),
                List.of(), DisseminationEngine.PROTOCOL_VERSION);
        engine.request(id("4"), restarted, List.of(), 300);
        assertEquals(id("4"), engine.view(TOPIC).orElseThrow().predecessor().nodeId());
    }

    private static DisseminationEngine engine(String local, int randomLinks, long seed) {
        return new DisseminationEngine(id(local), "127.0.0.1", 7000, randomLinks,
                10_000, new Random(seed), ignored -> { });
    }

    private static NavigationPeerDescriptor nav(String value, String topic) {
        return new NavigationPeerDescriptor(id(value), "127.0.0.1", 7001, List.of(topic), 100);
    }

    private static DisseminationPeerDescriptor peer(String value, String topic) {
        return new DisseminationPeerDescriptor(id(value), "127.0.0.1", 7001, List.of(topic), 100);
    }

    private static String id(String value) {
        return value.repeat(64);
    }

    private static List<String> ids(List<DisseminationPeerDescriptor> peers) {
        return peers.stream().map(DisseminationPeerDescriptor::nodeId).toList();
    }
}
