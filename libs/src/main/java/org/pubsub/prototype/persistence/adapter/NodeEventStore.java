package org.pubsub.prototype.persistence.adapter;

import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.auth.PublisherEventAuthenticator;
import org.pubsub.prototype.util.HexUtils;
import org.pubsub.prototype.util.files.AtomicFiles;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Durable, node-local subscriber history. All indexes are reconstructed from event files. */
public final class NodeEventStore {
    private final Path root;
    private final TreeMap<Key, EventEnvelope> events = new TreeMap<>();
    private final Map<String, Key> byId = new HashMap<>();
    private final PublisherEventAuthenticator authenticator = new PublisherEventAuthenticator();

    public NodeEventStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
            try (var paths = Files.walk(root, 3)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                    try {
                        EventEnvelope event = JsonSupport.MAPPER.readValue(path.toFile(), EventEnvelope.class);
                        Key key = key(event);
                        if (!path.equals(path(key)) || !EventCrypto.eventId(event).equals(event.eventId())
                                || events.putIfAbsent(key, event) != null
                                || byId.putIfAbsent(event.eventId(), key) != null) {
                            throw new IllegalArgumentException("event content or path conflict");
                        }
                        authenticator.authenticate(event);
                    } catch (IOException | RuntimeException ex) {
                        throw new IllegalStateException("Corrupt local event file: " + path, ex);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Unable to load local event store at " + root + ": " + ex.getMessage(), ex);
        }
    }

    public synchronized boolean store(EventEnvelope event) {
        if (!EventCrypto.eventId(event).equals(event.eventId()))
            throw new IllegalArgumentException("Invalid local event ID");
        authenticator.authenticate(event);
        Key key = key(event);
        EventEnvelope existing = events.get(key);
        if (existing != null) {
            if (!existing.equals(event)) throw new IllegalArgumentException("Event sequence conflict: " + key);
            return false;
        }
        if (byId.containsKey(event.eventId())) throw new IllegalArgumentException("Event ID conflict: " + event.eventId());
        try {
            AtomicFiles.write(path(key), JsonSupport.MAPPER.writeValueAsBytes(event));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to store local event " + key, ex);
        }
        events.put(key, event);
        byId.put(event.eventId(), key);
        return true;
    }

    public synchronized Optional<EventEnvelope> get(String topicId, String publisherKeyId, long sequence) {
        return Optional.ofNullable(events.get(new Key(
                HexUtils.normalizeSha256(topicId, "topicId"),
                HexUtils.normalizeSha256(publisherKeyId, "publisherKeyId"), sequence)));
    }

    public synchronized Optional<EventEnvelope> getById(String eventId) {
        Key key = byId.get(HexUtils.normalizeSha256(eventId, "eventId"));
        return Optional.ofNullable(key == null ? null : events.get(key));
    }

    /** Returns at most limit entries, ordered by topic, publisher, then sequence. */
    public synchronized Page list(String topicId, String publisherKeyId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 500) throw new IllegalArgumentException("offset must be >= 0 and limit must be 1..500");
        String topic = topicId == null ? null : HexUtils.normalizeSha256(topicId, "topicId");
        String publisher = publisherKeyId == null ? null : HexUtils.normalizeSha256(publisherKeyId, "publisherKeyId");
        List<EventEnvelope> page = new ArrayList<>(limit);
        int matched = 0;
        boolean more = false;
        for (var entry : events.entrySet()) {
            Key key = entry.getKey();
            if (topic != null && !topic.equals(key.topicId)) continue;
            if (publisher != null && !publisher.equals(key.publisherKeyId)) continue;
            if (matched++ < offset) continue;
            if (page.size() == limit) { more = true; break; }
            page.add(entry.getValue());
        }
        return new Page(List.copyOf(page), offset, limit, more);
    }

    public synchronized Map<String, Map<String, List<EventEnvelope>>> grouped() {
        Map<String, Map<String, List<EventEnvelope>>> result = new TreeMap<>();
        events.forEach((key, event) -> result.computeIfAbsent(key.topicId, ignored -> new TreeMap<>())
                .computeIfAbsent(key.publisherKeyId, ignored -> new ArrayList<>()).add(event));
        return result;
    }

    public synchronized int size() { return events.size(); }

    private Path path(Key key) {
        return root.resolve(key.topicId).resolve(key.publisherKeyId).resolve(key.sequence + ".json");
    }

    private static Key key(EventEnvelope event) {
        return new Key(event.topicId(), EventCrypto.publisherKeyId(event), event.sequenceNumber());
    }

    private record Key(String topicId, String publisherKeyId, long sequence) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int topic = topicId.compareTo(other.topicId);
            if (topic != 0) return topic;
            int publisher = publisherKeyId.compareTo(other.publisherKeyId);
            return publisher != 0 ? publisher : Long.compare(sequence, other.sequence);
        }
    }

    public record Page(List<EventEnvelope> events, int offset, int limit, boolean hasMore) { }
}
