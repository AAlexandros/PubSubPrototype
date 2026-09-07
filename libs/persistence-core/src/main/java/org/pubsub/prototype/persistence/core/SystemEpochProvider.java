package org.pubsub.prototype.persistence.core;

import java.time.Clock;

public final class SystemEpochProvider implements EpochProvider {
    private final Clock clock;
    private final long zeroTimeMillis;
    private final long epochLengthMillis;

    public SystemEpochProvider(Clock clock, long zeroTimeMillis, long epochLengthMillis) {
        if (epochLengthMillis <= 0) throw new IllegalArgumentException("epochLengthMillis must be positive");
        this.clock = clock;
        this.zeroTimeMillis = zeroTimeMillis;
        this.epochLengthMillis = epochLengthMillis;
    }

    @Override
    public long currentEpoch() {
        return Math.max(0, Math.floorDiv(clock.millis() - zeroTimeMillis, epochLengthMillis));
    }
}
