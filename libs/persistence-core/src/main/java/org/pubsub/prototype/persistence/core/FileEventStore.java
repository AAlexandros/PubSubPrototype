package org.pubsub.prototype.persistence.core;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.EventKeys;
import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.registry.TopicState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FileEventStore {
    private static final Logger LOG = LoggerFactory.getLogger(FileEventStore.class);
    private static final TypeReference<Map<String, PublisherProgress>> PROGRESS_TYPE = new TypeReference<>() { };
    private final Path root;
    private final Path events;
    private final Path topicLogs;
    private final Clock clock;
    private final EpochProvider epochs;

    public FileEventStore(Path root, Clock clock, EpochProvider epochs) {
        this.root = root;
        this.events = root.resolve("events");
        this.topicLogs = root.resolve("topic-logs");
        this.clock = clock;
        this.epochs = epochs;
        try {
            Files.createDirectories(events);
            Files.createDirectories(topicLogs);
            Files.createDirectories(root.resolve("metadata"));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize persistence store " + root, ex);
        }
    }

    public synchronized StoredEvent store(EventEnvelope envelope, TopicState topic) {
        StoredEvent record = newRecord(envelope, topic);
        String key = record.eventKey();
        Optional<StoredEvent> existing = get(key);
        if (existing.isPresent()) return existing.orElseThrow();
        try {
            AtomicFiles.write(eventPath(key), PersistenceJson.MAPPER.writeValueAsBytes(record));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to store event " + key, ex);
        }
        return record;
    }

    public StoredEvent newRecord(EventEnvelope envelope, TopicState topic) {
        long epoch = epochs.currentEpoch();
        return new StoredEvent(EventKeys.eventKey(envelope), envelope, Instant.now(clock), topic.retentionPeriod(), epoch,
                Math.addExact(epoch, topic.retentionPeriod()));
    }

    public synchronized void storeReplica(StoredEvent record) {
        if (!EventKeys.eventKey(record.eventEnvelope()).equals(record.eventKey())) {
            throw new IllegalArgumentException("eventKey does not match event envelope");
        }
        Optional<StoredEvent> existing = get(record.eventKey());
        StoredEvent selected = existing.filter(value -> !record.storedAt().isBefore(value.storedAt())).orElse(record);
        if (existing.isPresent() && selected == existing.orElseThrow()) return;
        try {
            AtomicFiles.write(eventPath(record.eventKey()), PersistenceJson.MAPPER.writeValueAsBytes(selected));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to store event replica " + record.eventKey(), ex);
        }
    }

    public synchronized Optional<StoredEvent> get(String eventKey) {
        Path path = eventPath(eventKey);
        if (!Files.exists(path)) return Optional.empty();
        try {
            StoredEvent event = PersistenceJson.MAPPER.readValue(path.toFile(), StoredEvent.class);
            if (event.expiresAfterEpoch() <= epochs.currentEpoch()) {
                Files.deleteIfExists(path);
                LOG.info("EVENT_EXPIRED eventKey={} eventId={} topicId={} publisherKeyId={} sequenceNumber={}",
                        event.eventKey(), event.eventEnvelope().eventId(), event.eventEnvelope().topicId(),
                        EventCrypto.publisherKeyId(event.eventEnvelope()), event.eventEnvelope().sequenceNumber());
                return Optional.empty();
            }
            return Optional.of(event);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read event " + eventKey, ex);
        }
    }

    public synchronized boolean containsRaw(String eventKey) {
        return Files.exists(eventPath(eventKey));
    }

    public synchronized boolean updateProgress(String topicId, PublisherProgress progress) {
        Map<String, PublisherProgress> values = new LinkedHashMap<>(readProgress(topicId));
        PublisherProgress previous = values.get(progress.publisherKeyId());
        if (previous != null && previous.latestSequenceNumber() >= progress.latestSequenceNumber()) return false;
        values.put(progress.publisherKeyId(), progress);
        writeProgress(topicId, values);
        return true;
    }

    public synchronized void mergeProgress(String topicId, List<PublisherProgress> progress) {
        progress.forEach(value -> updateProgress(topicId, value));
    }

    public synchronized List<PublisherProgress> publisherProgress(String topicId) {
        return readProgress(topicId).values().stream()
                .sorted(Comparator.comparing(PublisherProgress::publisherKeyId)).toList();
    }

    public synchronized int cleanupExpired() {
        if (!Files.exists(events)) return 0;
        int removed = 0;
        try (var paths = Files.list(events)) {
            for (Path path : paths.filter(value -> value.getFileName().toString().endsWith(".json")).toList()) {
                String key = path.getFileName().toString().replaceFirst("\\.json$", "");
                if (get(key).isEmpty() && !Files.exists(path)) removed++;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to clean expired events", ex);
        }
        return removed;
    }

    public synchronized List<String> eventKeys() {
        try (var paths = Files.list(events)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted().toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inventory events", ex);
        }
    }

    public Path root() {
        return root;
    }

    private Path eventPath(String eventKey) {
        return events.resolve(PersistenceHex.require256(eventKey, "eventKey") + ".json");
    }

    private Path progressPath(String topicId) {
        return topicLogs.resolve(EventKeys.topicLogKey(topicId) + ".json");
    }

    private Map<String, PublisherProgress> readProgress(String topicId) {
        Path path = progressPath(topicId);
        if (!Files.exists(path)) return Map.of();
        try {
            return PersistenceJson.MAPPER.readValue(path.toFile(), PROGRESS_TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read topic log " + topicId, ex);
        }
    }

    private void writeProgress(String topicId, Map<String, PublisherProgress> values) {
        try {
            AtomicFiles.write(progressPath(topicId), PersistenceJson.MAPPER.writeValueAsBytes(values));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write topic log " + topicId, ex);
        }
    }
}
