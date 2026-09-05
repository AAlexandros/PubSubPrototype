package org.pubsub.prototype.event;

public record EventValidationResult(boolean accepted, EventRejectReason reason, String publisherKeyId, EventSequenceStatus sequenceStatus) {
    public static EventValidationResult accepted(String publisherKeyId) {
        return new EventValidationResult(true, null, publisherKeyId, EventSequenceStatus.ACCEPTED);
    }

    public static EventValidationResult duplicate(String publisherKeyId) {
        return new EventValidationResult(false, null, publisherKeyId, EventSequenceStatus.DUPLICATE);
    }

    public static EventValidationResult rejected(EventRejectReason reason, String publisherKeyId, EventSequenceStatus sequenceStatus) {
        return new EventValidationResult(false, reason, publisherKeyId, sequenceStatus);
    }
}
