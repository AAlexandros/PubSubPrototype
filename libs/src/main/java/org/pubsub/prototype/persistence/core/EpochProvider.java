package org.pubsub.prototype.persistence.core;

/** Supplies the current Cardano epoch for event-retention decisions. */
@FunctionalInterface
public interface EpochProvider {
    /**
     * Returns the epoch reported by its Cardano source, failing if it cannot be determined reliably.
     *
     * @return the non-negative Cardano epoch
     * @throws IllegalStateException if the Cardano source is unavailable or not sufficiently synced
     */
    long currentEpoch();
}
