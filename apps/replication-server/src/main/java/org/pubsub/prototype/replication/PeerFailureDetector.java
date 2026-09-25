package org.pubsub.prototype.replication;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.pubsub.prototype.replication.ReplicationConfigConstants.FAILURE_PROBE_ATTEMPTS;
import static org.pubsub.prototype.util.Validators.requirePositive;

final class PeerFailureDetector {
    enum Transition { NONE, SUSPECTED, CONFIRMED_DOWN, RECOVERED }

    private final int attemptsRequired;
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Set<String> confirmedDown = ConcurrentHashMap.newKeySet();

    PeerFailureDetector(int attemptsRequired) {
        this.attemptsRequired = requirePositive(attemptsRequired, FAILURE_PROBE_ATTEMPTS);
    }

    Transition failed(String serverId) {
        int attempts = failures.merge(serverId, 1, Integer::sum);
        if (attempts >= attemptsRequired && confirmedDown.add(serverId)) return Transition.CONFIRMED_DOWN;
        return attempts == 1 ? Transition.SUSPECTED : Transition.NONE;
    }

    Transition succeeded(String serverId) {
        boolean wasSuspected = failures.remove(serverId) != null;
        boolean wasDown = confirmedDown.remove(serverId);
        return wasSuspected || wasDown ? Transition.RECOVERED : Transition.NONE;
    }

    Transition confirmReported(String serverId) {
        failures.put(serverId, attemptsRequired);
        return confirmedDown.add(serverId) ? Transition.CONFIRMED_DOWN : Transition.NONE;
    }

    void retain(Set<String> activeServerIds) {
        failures.keySet().retainAll(activeServerIds);
        confirmedDown.retainAll(activeServerIds);
    }

    Set<String> suspected() {
        Set<String> result = new TreeSet<>(failures.keySet());
        result.removeAll(confirmedDown);
        return result;
    }

    Set<String> confirmedDown() {
        return new TreeSet<>(confirmedDown);
    }
}
