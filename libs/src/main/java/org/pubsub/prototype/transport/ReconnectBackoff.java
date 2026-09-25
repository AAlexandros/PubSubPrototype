package org.pubsub.prototype.transport;

import java.util.OptionalLong;

import static org.pubsub.prototype.util.Validators.require;

/**
 * Supplies exponentially increasing delays for a limited reconnect sequence.
 *
 * <p>After the initial connection attempt fails, at most {@value #MAX_RETRIES}
 * retries are allowed, giving three connection attempts in total. A successful
 * authenticated connection must call {@link #reset()} so a later disconnection
 * starts a fresh retry sequence.</p>
 */
public final class ReconnectBackoff {
    public static final int MAX_RETRIES = 2;

    private final long initialMs;
    private final long maxMs;
    private long currentMs;
    private int retries;

    public ReconnectBackoff(long initialMs, long maxMs) {
        require(initialMs > 0 && maxMs >= initialMs, "Invalid reconnect backoff bounds");
        this.initialMs = initialMs;
        this.maxMs = maxMs;
        this.currentMs = initialMs;
    }

    /**
     * Returns the delay for the next retry, or an empty value when both retries
     * have already been scheduled.
     */
    public synchronized OptionalLong nextDelayMs() {
        if (retries >= MAX_RETRIES) {
            return OptionalLong.empty();
        }
        long delay = currentMs;
        currentMs = Math.min(maxMs, currentMs * 2);
        retries++;
        return OptionalLong.of(delay);
    }

    /** Resets the delay and retry budget after a successful handshake. */
    public synchronized void reset() {
        currentMs = initialMs;
        retries = 0;
    }
}
