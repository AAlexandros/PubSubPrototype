package org.pubsub.prototype.replication;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerFailureDetectorTest {
    @Test
    void requiresConsecutiveFailuresAndClearsSuspicionOnSuccess() {
        PeerFailureDetector detector = new PeerFailureDetector(3);

        assertEquals(PeerFailureDetector.Transition.SUSPECTED, detector.failed("peer"));
        assertTrue(detector.confirmedDown().isEmpty());
        assertEquals(PeerFailureDetector.Transition.RECOVERED, detector.succeeded("peer"));
        assertTrue(detector.suspected().isEmpty());

        assertEquals(PeerFailureDetector.Transition.SUSPECTED, detector.failed("peer"));
        assertEquals(PeerFailureDetector.Transition.NONE, detector.failed("peer"));
        assertEquals(PeerFailureDetector.Transition.CONFIRMED_DOWN, detector.failed("peer"));
        assertEquals(Set.of("peer"), detector.confirmedDown());
        assertEquals(PeerFailureDetector.Transition.NONE, detector.failed("peer"));
        assertEquals(PeerFailureDetector.Transition.RECOVERED, detector.succeeded("peer"));
        assertTrue(detector.confirmedDown().isEmpty());
    }

    @Test
    void forgetsPeersThatLeaveMembership() {
        PeerFailureDetector detector = new PeerFailureDetector(1);
        detector.failed("departed");
        detector.retain(Set.of("remaining"));
        assertTrue(detector.confirmedDown().isEmpty());
    }
}
