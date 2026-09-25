package org.pubsub.prototype.persistence.core;

import java.time.Clock;

import static org.pubsub.prototype.util.PersistenceConstants.EPOCH_LENGTH_MILLIS_FIELD;
import static org.pubsub.prototype.util.Validators.requirePositive;

public final class SystemEpochProvider implements EpochProvider {
    private final Clock clock;
    private final long zeroTimeMillis;
    private final long epochLengthMillis;

    public SystemEpochProvider(Clock clock, long zeroTimeMillis, long epochLengthMillis) {
        requirePositive(epochLengthMillis, EPOCH_LENGTH_MILLIS_FIELD);
        this.clock = clock;
        this.zeroTimeMillis = zeroTimeMillis;
        this.epochLengthMillis = epochLengthMillis;
    }

    @Override
    public long currentEpoch() {
        return Math.max(0, Math.floorDiv(clock.millis() - zeroTimeMillis, epochLengthMillis));
    }
}
