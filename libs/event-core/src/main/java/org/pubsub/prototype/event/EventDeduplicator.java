package org.pubsub.prototype.event;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EventDeduplicator {
    private final int maxEntries;
    private final Map<String, String> accepted;

    public EventDeduplicator(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be greater than zero");
        }
        this.maxEntries = maxEntries;
        this.accepted = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized EventSequenceStatus checkAndRemember(EventEnvelope event, String publisherKeyId) {
        String key = event.topicId() + ":" + publisherKeyId + ":" + event.sequenceNumber();
        String existing = accepted.get(key);
        if (existing != null && existing.equals(event.eventId())) {
            return EventSequenceStatus.DUPLICATE;
        }
        if (existing != null) {
            return EventSequenceStatus.CONFLICT;
        }
        accepted.put(key, event.eventId());
        trim();
        return EventSequenceStatus.ACCEPTED;
    }

    private void trim() {
        while (accepted.size() > maxEntries) {
            String eldest = accepted.keySet().iterator().next();
            accepted.remove(eldest);
        }
    }
}
