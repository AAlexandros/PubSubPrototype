package org.pubsub.prototype.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationViewTest {
    private static final String SELF = "0".repeat(64);
    private static final TopicOrdering ORDERING = TopicOrdering.of(
            List.of("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)));

    private static NavigationPeerDescriptor peer(String id, String... topicIds) {
        return new NavigationPeerDescriptor(id.repeat(64), "127.0.0.1", 7000, List.of(topicIds), 0);
    }

    private static NavigationPeerDescriptor peer(String id, long freshness, String... topicIds) {
        return new NavigationPeerDescriptor(id.repeat(64), "127.0.0.1", 7000, List.of(topicIds), freshness);
    }

    @Test
    void exactTopicCandidateBeatsNearbyTopicCandidate() {
        NavigationView view = new NavigationView(2);
        // Target ordinal 0 ("a"). Peer 1 subscribes to "b" (ordinal 1, distance 1); peer 2 subscribes to "a" (distance 0).
        NavigationPeerDescriptor near = peer("1", "b".repeat(64));
        NavigationPeerDescriptor exact = peer("2", "a".repeat(64));

        view.offer(0, near, ORDERING, SELF);
        view.offer(0, exact, ORDERING, SELF);
        // Fill the bucket to capacity so ranking must evict the worse candidate.
        view.offer(0, peer("3", "b".repeat(64)), ORDERING, SELF);

        List<NavigationPeerDescriptor> candidates = view.candidatesFor(0);
        assertTrue(candidates.contains(exact));
    }

    @Test
    void nearerTopicBeatsFartherTopic() {
        NavigationView view = new NavigationView(1);
        TopicOrdering ordering = TopicOrdering.of(List.of(
                "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
                "e".repeat(64), "f".repeat(64), "g".repeat(64), "h".repeat(64)));
        // target ordinal 0: "b"=1 (distance 1), "e"=4 (distance 4).
        NavigationPeerDescriptor nearer = peer("1", "b".repeat(64));
        NavigationPeerDescriptor farther = peer("2", "e".repeat(64));

        view.offer(0, farther, ordering, SELF);
        boolean changed = view.offer(0, nearer, ordering, SELF);

        assertTrue(changed);
        assertEquals(List.of(nearer), view.candidatesFor(0));
    }

    @Test
    void freshnessBreaksEqualDistanceTies() {
        NavigationView view = new NavigationView(1);
        NavigationPeerDescriptor stale = peer("1", 10, "b".repeat(64));
        NavigationPeerDescriptor fresh = peer("2", 20, "b".repeat(64));

        view.offer(0, stale, ORDERING, SELF);
        boolean changed = view.offer(0, fresh, ORDERING, SELF);

        assertTrue(changed);
        assertEquals(List.of(fresh), view.candidatesFor(0));
    }

    @Test
    void viewIsBoundedToCapacityPerTarget() {
        NavigationView view = new NavigationView(2);
        view.offer(0, peer("1", "b".repeat(64)), ORDERING, SELF);
        view.offer(0, peer("2", "c".repeat(64)), ORDERING, SELF);
        view.offer(0, peer("3", "d".repeat(64)), ORDERING, SELF);

        assertEquals(2, view.candidatesFor(0).size());
    }

    @Test
    void selfIsNeverRetained() {
        NavigationView view = new NavigationView(2);

        boolean changed = view.offer(0, peer("0", "b".repeat(64)), ORDERING, SELF);

        assertFalse(changed);
        assertTrue(view.candidatesFor(0).isEmpty());
    }

    @Test
    void duplicateNodeIdIsNotRetainedTwiceInSameSlot() {
        NavigationView view = new NavigationView(2);
        view.offer(0, peer("1", "b".repeat(64)), ORDERING, SELF);
        view.offer(0, peer("1", "c".repeat(64)), ORDERING, SELF);

        assertEquals(1, view.candidatesFor(0).size());
        assertEquals(Set.of("1".repeat(64)), Set.of(view.candidatesFor(0).getFirst().nodeId()));
    }
}
