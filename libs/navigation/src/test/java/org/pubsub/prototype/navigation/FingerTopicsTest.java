package org.pubsub.prototype.navigation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerTopicsTest {

    @Test
    void cyclicDistanceIsShortestPath() {
        assertEquals(0, FingerTopics.distance(3, 3, 16));
        assertEquals(1, FingerTopics.distance(0, 1, 16));
        assertEquals(1, FingerTopics.distance(0, 15, 16));
        assertEquals(8, FingerTopics.distance(0, 8, 16));
        assertEquals(7, FingerTopics.distance(0, 9, 16));
    }

    @Test
    void fingerTopicsForSubscriptionIncludeOwnTopicAndPowersOfBase() {
        // T=16, base=2, x=0: {0} u {0+/-1, 0+/-2, 0+/-4, 0+/-8} stopping once step > 8.
        Set<Integer> targets = FingerTopics.forSubscription(0, 16, 2);

        assertEquals(Set.of(0, 1, 15, 2, 14, 4, 12, 8), targets);
    }

    @Test
    void stopsWhenDistanceExceedsHalfTheRing() {
        // T=5, base=2, x=0: step=1 (<=2.5), step=2 (<=2.5), step=4 (>2.5, excluded).
        Set<Integer> targets = FingerTopics.forSubscription(0, 5, 2);

        assertEquals(Set.of(0, 1, 4, 2, 3), targets);
    }

    @Test
    void unionAcrossMultipleSubscriptionsIsDeduplicated() {
        Set<Integer> union = FingerTopics.forSubscriptions(Set.of(0, 8), 16, 2);

        assertTrue(union.containsAll(FingerTopics.forSubscription(0, 16, 2)));
        assertTrue(union.containsAll(FingerTopics.forSubscription(8, 16, 2)));
    }

    @Test
    void emptyOrdinalSetProducesNoTargets() {
        assertEquals(Set.of(), FingerTopics.forSubscription(0, 0, 2));
        assertEquals(Set.of(), FingerTopics.forSubscriptions(Set.of(), 16, 2));
    }
}
