package org.pubsub.prototype.navigation;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Finger-topic target computation: x, x +/- b^0, x +/- b^1, ... stopping once the distance exceeds T/2. */
public final class FingerTopics {
    private FingerTopics() {
    }

    public static Set<Integer> forSubscription(int topicOrdinal, int size, int base) {
        if (size <= 0) {
            return Set.of();
        }
        int origin = Math.floorMod(topicOrdinal, size);
        Set<Integer> targets = new LinkedHashSet<>();
        targets.add(origin);
        double step = 1;
        while (step <= size / 2.0) {
            targets.add(Math.floorMod(origin + (long) step, size));
            targets.add(Math.floorMod(origin - (long) step, size));
            step *= base;
        }
        return Set.copyOf(targets);
    }

    public static Set<Integer> forSubscriptions(Collection<Integer> topicOrdinals, int size, int base) {
        Set<Integer> targets = new LinkedHashSet<>();
        for (int ordinal : topicOrdinals) {
            targets.addAll(forSubscription(ordinal, size, base));
        }
        return Set.copyOf(targets);
    }

    public static int distance(int a, int b, int size) {
        if (size <= 0) {
            return 0;
        }
        int diff = Math.floorMod(a - b, size);
        return Math.min(diff, size - diff);
    }
}
