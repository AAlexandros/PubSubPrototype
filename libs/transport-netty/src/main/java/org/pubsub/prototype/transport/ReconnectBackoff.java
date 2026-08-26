package org.pubsub.prototype.transport;

public final class ReconnectBackoff {
    private final long initialMs;
    private final long maxMs;
    private long currentMs;

    public ReconnectBackoff(long initialMs, long maxMs) {
        if (initialMs <= 0 || maxMs < initialMs) {
            throw new IllegalArgumentException("Invalid reconnect backoff bounds");
        }
        this.initialMs = initialMs;
        this.maxMs = maxMs;
        this.currentMs = initialMs;
    }

    public synchronized long nextDelayMs() {
        long delay = currentMs;
        currentMs = Math.min(maxMs, currentMs * 2);
        return delay;
    }

    public synchronized void reset() {
        currentMs = initialMs;
    }
}
