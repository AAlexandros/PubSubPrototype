package org.pubsub.prototype.event;

public enum EventRejectReason {
    UNKNOWN_TOPIC,
    INACTIVE_TOPIC,
    INVALID_EVENT_ID,
    INVALID_SIGNATURE,
    UNAUTHORIZED_PUBLISHER,
    INVALID_SEQUENCE
}
