package org.pubsub.prototype.persistence;

public record DeliveryProgress(long lastDeliveredSequenceNumber, long lastDeliveredTimestamp) {
    public DeliveryProgress {
        if (lastDeliveredSequenceNumber < -1 || lastDeliveredTimestamp < 0) {
            throw new IllegalArgumentException("invalid delivery progress");
        }
    }

    public static DeliveryProgress empty() {
        return new DeliveryProgress(-1, 0);
    }
}
