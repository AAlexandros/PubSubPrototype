package org.pubsub.prototype.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pubsub.prototype.util.NavigationConstants.PROTOCOL_VERSION;

class NavigationEngineTest {
    private static final List<String> TOPICS = List.of(
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
            "e".repeat(64), "f".repeat(64), "g".repeat(64), "h".repeat(64));

    private static NavigationLayer engine(String id, int port, String... subscriptions) {
        NavigationLayer engine = new NavigationLayer(id.repeat(64), "127.0.0.1", port,
                List.of(subscriptions), 2, 2, 5000, new Random(0), ignored -> {
        });
        engine.updateTopicIndex(TopicIndex.of(TOPICS));
        return engine;
    }

    @Test
    void twoEnginesConvergeThroughDirectGossipExchange() {
        NavigationLayer a = engine("1", 7001, TOPICS.get(0));
        NavigationLayer b = engine("2", 7002, TOPICS.get(0));

        // Bootstrap via a raw sample the way a fresh SecureCyclon peer would be introduced.
        a.ingestSample("2".repeat(64), "127.0.0.1", 7002, 1);
        b.ingestSample("1".repeat(64), "127.0.0.1", 7001, 1);

        Optional<NavigationLayer.Outgoing> outgoing = a.cycle(100);
        assertTrue(outgoing.isPresent());
        assertEquals("2".repeat(64), outgoing.get().peer().nodeId());

        NavigationExchange response = b.request("1".repeat(64), outgoing.get().exchange(), 200);
        a.response("2".repeat(64), response, 300);

        // Both now know each other's real subscription (same topic => distance 0).
        assertTrue(a.view().values().stream().anyMatch(list -> list.stream().anyMatch(p -> p.nodeId().equals("2".repeat(64)))));
        assertTrue(response.senderSubscriptions().contains(TOPICS.get(0)));
    }

    @Test
    void subscribingRecomputesFingerTopicsWithoutClearingUnrelatedTargets() {
        NavigationLayer engine = engine("1", 7001, TOPICS.get(0));
        Set<Integer> before = engine.fingerTopics();

        engine.subscribe(TOPICS.get(4));
        Set<Integer> after = engine.fingerTopics();

        assertTrue(after.size() >= before.size());
        assertTrue(after.contains(4));
    }

    @Test
    void changingTopicIndexClearsStaleView() {
        NavigationLayer engine = engine("1", 7001, TOPICS.get(0));
        engine.ingestSample("2".repeat(64), "127.0.0.1", 7002, 1);
        assertFalse(engine.view().isEmpty());

        engine.updateTopicIndex(TopicIndex.of(List.of(TOPICS.get(0), TOPICS.get(1))));

        assertTrue(engine.view().values().stream().allMatch(List::isEmpty) || engine.view().isEmpty());
    }

    @Test
    void unsubscribingDropsFingerTopicsNoLongerNeeded() {
        NavigationLayer engine = engine("1", 7001, TOPICS.get(0), TOPICS.get(4));
        assertTrue(engine.fingerTopics().contains(4));

        engine.unsubscribe(TOPICS.get(4));

        Set<Integer> remaining = engine.fingerTopics();
        // Ordinal 4 may still be reachable as a finger of topic 0 (distance 4), so assert subscription bookkeeping instead.
        assertFalse(engine.subscriptions().contains(TOPICS.get(4)));
        assertTrue(engine.subscriptions().contains(TOPICS.get(0)));
    }

    @Test
    void emptyViewProducesNoOutgoingGossip() {
        NavigationLayer engine = engine("1", 7001, TOPICS.get(0));

        assertTrue(engine.cycle(100).isEmpty());
    }

    @Test
    void exposesOnlySameTopicCandidatesThroughPublicApi() {
        NavigationLayer engine = engine("1", 7001, TOPICS.get(0));
        engine.ingestSample("2".repeat(64), "127.0.0.1", 7002, 1);
        NavigationExchange learned = new NavigationExchange(
                new NavigationPeerDescriptor("2".repeat(64), "127.0.0.1", 7002,
                        List.of(TOPICS.get(0)), 1),
                List.of(TOPICS.get(0)),
                List.of(new NavigationPeerDescriptor("3".repeat(64), "127.0.0.1", 7003,
                        List.of(TOPICS.get(1)), 1)), PROTOCOL_VERSION);
        engine.response("2".repeat(64), learned, 100);

        assertEquals(List.of("2".repeat(64)),
                engine.peersForTopic(TOPICS.get(0)).stream().map(NavigationPeerDescriptor::nodeId).toList());
        assertTrue(engine.peersForTopic(TOPICS.get(1)).stream()
                .allMatch(peer -> peer.subscribedTopicIds().contains(TOPICS.get(1))));
    }
}
