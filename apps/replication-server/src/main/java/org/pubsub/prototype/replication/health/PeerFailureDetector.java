package org.pubsub.prototype.replication.health;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.pubsub.prototype.replication.config.ReplicationConfigConstants.FAILURE_PROBE_ATTEMPTS;
import static org.pubsub.prototype.util.validation.Validators.requirePositive;

/**
 * Tracks probe results for peers selected by replica maintenance. A peer is
 * reported as suspected before its configured failure threshold, then as
 * confirmed down. A successful probe clears that state. Replication and
 * repair use the confirmed-down set when choosing servers.
 */
public final class PeerFailureDetector {
    public enum Transition { NONE, SUSPECTED, CONFIRMED_DOWN, RECOVERED }

    private final int attemptsRequired;
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Set<String> confirmedDown = ConcurrentHashMap.newKeySet();

    public PeerFailureDetector(int attemptsRequired) {
        this.attemptsRequired = requirePositive(attemptsRequired, FAILURE_PROBE_ATTEMPTS);
    }

    public int attemptsRequired() {
        return attemptsRequired;
    }

    /** Records a failed probe; confirms the peer after the configured number of failures. */
    public Transition failed(String serverId) {
        int attempts = failures.merge(serverId, 1, Integer::sum);
        if (attempts >= attemptsRequired && confirmedDown.add(serverId)) return Transition.CONFIRMED_DOWN;
        return attempts == 1 ? Transition.SUSPECTED : Transition.NONE;
    }

    /** Clears the peer's failure history after a successful probe. */
    public Transition succeeded(String serverId) {
        boolean wasSuspected = failures.remove(serverId) != null;
        boolean wasDown = confirmedDown.remove(serverId);
        return wasSuspected || wasDown ? Transition.RECOVERED : Transition.NONE;
    }

    /** Forgets peers no longer present in the current membership. */
    public void retain(Set<String> activeServerIds) {
        failures.keySet().retainAll(activeServerIds);
        confirmedDown.retainAll(activeServerIds);
    }

    /** Returns peers with failed probes that are not yet confirmed down. */
    public Set<String> suspected() {
        Set<String> result = new TreeSet<>(failures.keySet());
        result.removeAll(confirmedDown);
        return result;
    }

    /** Returns peers excluded from routing and repair as confirmed down. */
    public Set<String> confirmedDown() {
        return new TreeSet<>(confirmedDown);
    }
}
