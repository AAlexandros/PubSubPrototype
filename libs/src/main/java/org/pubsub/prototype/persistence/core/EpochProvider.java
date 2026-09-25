package org.pubsub.prototype.persistence.core;

@FunctionalInterface
public interface EpochProvider {
    long currentEpoch();
}
