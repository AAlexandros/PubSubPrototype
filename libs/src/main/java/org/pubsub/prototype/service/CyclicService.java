package org.pubsub.prototype.service;

/** A service that owns a periodic work cycle and its lifecycle. */
public interface CyclicService extends AutoCloseable {
    /** Starts the service's scheduled work. */
    void start();

    /** Executes one cycle of the service's main periodic work. */
    void runCycle();

    /** Stops scheduled work and releases the service's resources. */
    @Override
    void close();
}
