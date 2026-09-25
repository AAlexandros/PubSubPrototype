package org.pubsub.prototype.navigation;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.pubsub.prototype.util.Validators.require;

/**
 * Computes the topic-ring positions for which a node should retain useful peers.
 * For topic ordinal {@code x}, targets are {@code x} and {@code x +/- base^k},
 * stopping beyond half the ring.
 */
public final class FingerTopics {

    private FingerTopics() {
    }

    /**
     * Computes targets for one valid topic ordinal. An empty topic index produces no targets.
     *
     * @throws IllegalArgumentException if the ordinal is outside a non-empty topic index
     */
    public static Set<Integer> forSubscription(int topicOrdinal, int size, int base) {
        if (size <= 0) {
            return Set.of();
        }
        require(topicOrdinal >= 0 && topicOrdinal < size,
                "topicOrdinal must be between 0 and " + (size - 1));

        Set<Integer> targets = new LinkedHashSet<>();
        targets.add(topicOrdinal);
        double step = 1;
        // Compute finger-topic targets from both directions around the circle
        while (step <= size / 2.0) {
            targets.add(Math.floorMod(topicOrdinal + (long) step, size));
            targets.add(Math.floorMod(topicOrdinal - (long) step, size));
            // Double the step for exponential growth
            step *= base;
        }
        return Set.copyOf(targets);
    }

    // Computes targets for multiple topic ordinals
    public static Set<Integer> forSubscriptions(Collection<Integer> topicOrdinals, int size, int base) {
        Set<Integer> targets = new LinkedHashSet<>();
        for (int ordinal : topicOrdinals) {
            targets.addAll(forSubscription(ordinal, size, base));
        }
        return Set.copyOf(targets);
    }

    // Compute the distance between two topic ordinals, using modulo arithmetic
    public static int distance(int a, int b, int size) {
        if (size <= 0) {
            return 0;
        }
        int diff = Math.floorMod(a - b, size);
        return Math.min(diff, size - diff);
    }
}
