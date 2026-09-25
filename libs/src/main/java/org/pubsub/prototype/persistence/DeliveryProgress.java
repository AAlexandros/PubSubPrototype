package org.pubsub.prototype.persistence;

import static org.pubsub.prototype.util.PersistenceConstants.LAST_DELIVERED_SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.LAST_DELIVERED_TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.Validation.start;

public record DeliveryProgress(long lastDeliveredSequenceNumber, long lastDeliveredTimestamp) {
    public DeliveryProgress {
        start()
                .require(lastDeliveredSequenceNumber >= -1,
                        LAST_DELIVERED_SEQUENCE_NUMBER_FIELD + " must be at least -1")
                .nonNegative(lastDeliveredTimestamp, LAST_DELIVERED_TIMESTAMP_FIELD)
                .throwIfInvalid();
    }

    public static DeliveryProgress empty() {
        return new DeliveryProgress(-1, 0);
    }
}
