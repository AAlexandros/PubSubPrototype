package org.pubsub.prototype.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class EventSequenceStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<Map<String, Long>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path stateFile;
    private final Path eventDir;
    private final Map<String, Long> nextSequences;

    public EventSequenceStore(Path runtimeDir) {
        try {
            Files.createDirectories(runtimeDir);
            this.stateFile = runtimeDir.resolve("event-sequences.json");
            this.eventDir = runtimeDir.resolve("events");
            this.nextSequences = load();
            recoverFromPersistedEvents();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize event sequence store at " + runtimeDir, ex);
        }
    }

    public synchronized long reserve(String topicId, String publisherKeyId) {
        return nextSequences.getOrDefault(key(topicId, publisherKeyId), 0L);
    }

    public synchronized void commit(String topicId, String publisherKeyId, long sequenceNumber) {
        String key = key(topicId, publisherKeyId);
        long current = nextSequences.getOrDefault(key, 0L);
        if (sequenceNumber >= current) {
            nextSequences.put(key, sequenceNumber + 1);
            persist();
        }
    }

    public synchronized long nextSequence(String topicId, String publisherKeyId) {
        return nextSequences.getOrDefault(key(topicId, publisherKeyId), 0L);
    }

    private Map<String, Long> load() throws IOException {
        if (!Files.exists(stateFile)) {
            return new HashMap<>();
        }
        return new HashMap<>(MAPPER.readValue(stateFile.toFile(), MAP_TYPE));
    }

    private void recoverFromPersistedEvents() throws IOException {
        if (!Files.exists(eventDir)) {
            return;
        }
        try (var paths = Files.walk(eventDir, 3)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                Path publisherDir = path.getParent();
                Path topicDir = publisherDir == null ? null : publisherDir.getParent();
                if (topicDir == null || !topicDir.getParent().equals(eventDir)) {
                    return;
                }
                long sequenceNumber = sequenceNumber(path.getFileName().toString());
                if (sequenceNumber < 0) {
                    return;
                }
                String key = key(topicDir.getFileName().toString(), publisherDir.getFileName().toString());
                long current = nextSequences.getOrDefault(key, 0L);
                if (sequenceNumber >= current) {
                    nextSequences.put(key, sequenceNumber + 1);
                }
            });
        }
        persist();
    }

    private static long sequenceNumber(String fileName) {
        int separator = fileName.indexOf('-');
        if (separator <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(fileName.substring(0, separator));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void persist() {
        try {
            MAPPER.writeValue(stateFile.toFile(), nextSequences);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist event sequence state", ex);
        }
    }

    private static String key(String topicId, String publisherKeyId) {
        return topicId + ":" + publisherKeyId;
    }
}
