package org.pubsub.prototype.persistence.core;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.EventKeys;
import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.registry.TopicState;
import org.pubsub.prototype.util.JsonSupport;
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
import java.util.Set;
import java.util.TreeSet;

import static org.pubsub.prototype.util.PersistenceConstants.EVENTS_DIRECTORY;
import static org.pubsub.prototype.util.PersistenceConstants.EVENT_KEY_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.JSON_EXTENSION;
import static org.pubsub.prototype.util.PersistenceConstants.JSON_EXTENSION_PATTERN;
import static org.pubsub.prototype.util.PersistenceConstants.METADATA_DIRECTORY;
import static org.pubsub.prototype.util.PersistenceConstants.SERVER_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.SERVER_ID_FILE;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_INDEX_FILE;
import static org.pubsub.prototype.util.PersistenceConstants.TOPIC_LOGS_DIRECTORY;
import static org.pubsub.prototype.util.Validators.require;

public final class FileEventStore {
    private static final Logger LOG = LoggerFactory.getLogger(FileEventStore.class);
    private static final TypeReference<Map<String, PublisherProgress>> PROGRESS_TYPE = new TypeReference<>() { };
    private static final TypeReference<Set<String>> TOPIC_INDEX_TYPE = new TypeReference<>() { };
    private final Path root;
    private final Path events;
    private final Path topicLogs;
    private final Clock clock;
    private final EpochProvider epochs;

    public FileEventStore(Path root, Clock clock, EpochProvider epochs) {
        this.root = root;
        this.events = root.resolve(EVENTS_DIRECTORY);
        this.topicLogs = root.resolve(TOPIC_LOGS_DIRECTORY);
        this.clock = clock;
        this.epochs = epochs;
        try {
            Files.createDirectories(events);
            Files.createDirectories(topicLogs);
            Files.createDirectories(root.resolve(METADATA_DIRECTORY));
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
            AtomicFiles.write(eventPath(key), JsonSupport.MAPPER.writeValueAsBytes(record));
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
        require(EventKeys.eventKey(record.eventEnvelope()).equals(record.eventKey()),
                "eventKey does not match event envelope");
        Optional<StoredEvent> existing = get(record.eventKey());
        StoredEvent selected = existing.filter(value -> !record.storedAt().isBefore(value.storedAt())).orElse(record);
        if (existing.isPresent() && selected == existing.orElseThrow()) return;
        try {
            AtomicFiles.write(eventPath(record.eventKey()), JsonSupport.MAPPER.writeValueAsBytes(selected));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to store event replica " + record.eventKey(), ex);
        }
    }

    public synchronized Optional<StoredEvent> get(String eventKey) {
        Path path = eventPath(eventKey);
        if (!Files.exists(path)) return Optional.empty();
        try {
            StoredEvent event = JsonSupport.MAPPER.readValue(path.toFile(), StoredEvent.class);
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
        if (previous != null && (previous.latestSequenceNumber() > progress.latestSequenceNumber()
                || (previous.latestSequenceNumber() == progress.latestSequenceNumber()
                && previous.latestTimestamp() >= progress.latestTimestamp()))) return false;
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
            for (Path path : paths.filter(value -> value.getFileName().toString().endsWith(JSON_EXTENSION)).toList()) {
                String key = path.getFileName().toString().replaceFirst(JSON_EXTENSION_PATTERN, "");
                if (get(key).isEmpty() && !Files.exists(path)) removed++;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to clean expired events", ex);
        }
        return removed;
    }

    public synchronized List<String> eventKeys() {
        try (var paths = Files.list(events)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(JSON_EXTENSION))
                    .map(path -> path.getFileName().toString().replaceFirst(JSON_EXTENSION_PATTERN, ""))
                    .sorted().toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inventory events", ex);
        }
    }

    public synchronized List<StoredEvent> eventRecords() {
        List<StoredEvent> records = new ArrayList<>();
        for (String eventKey : eventKeys()) get(eventKey).ifPresent(records::add);
        return records.stream().sorted(Comparator.comparing(StoredEvent::eventKey)).toList();
    }

    public synchronized boolean deleteEvent(String eventKey) {
        try {
            return Files.deleteIfExists(eventPath(eventKey));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to delete event " + eventKey, ex);
        }
    }

    public synchronized List<String> topicIds() {
        Path path = topicIndexPath();
        if (!Files.exists(path)) return List.of();
        try {
            return JsonSupport.MAPPER.readValue(path.toFile(), TOPIC_INDEX_TYPE).stream()
                    .filter(this::hasTopicLog).sorted().toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read topic-log index", ex);
        }
    }

    public synchronized boolean hasTopicLog(String topicId) {
        return Files.exists(progressPath(topicId));
    }

    public synchronized boolean deleteTopicLog(String topicId) {
        try {
            boolean removed = Files.deleteIfExists(progressPath(topicId));
            if (removed) {
                Set<String> topics = new TreeSet<>(topicIds());
                topics.remove(PersistenceHex.require256(topicId, TOPIC_ID_FIELD));
                AtomicFiles.write(topicIndexPath(), JsonSupport.MAPPER.writeValueAsBytes(topics));
            }
            return removed;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to delete topic log " + topicId, ex);
        }
    }

    public synchronized void ensureServerIdentity(String serverId) {
        String normalized = PersistenceHex.require256(serverId, SERVER_ID_FIELD);
        Path path = root.resolve(METADATA_DIRECTORY).resolve(SERVER_ID_FILE);
        try {
            if (Files.exists(path)) {
                String persisted = Files.readString(path).trim();
                if (!normalized.equals(persisted)) {
                    throw new IllegalStateException("storage belongs to replication server " + persisted);
                }
                return;
            }
            AtomicFiles.write(path, (normalized + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist replication server identity", ex);
        }
    }

    public Path root() {
        return root;
    }

    private Path eventPath(String eventKey) {
        return events.resolve(PersistenceHex.require256(eventKey, EVENT_KEY_FIELD) + JSON_EXTENSION);
    }

    private Path progressPath(String topicId) {
        return topicLogs.resolve(EventKeys.topicLogKey(topicId) + JSON_EXTENSION);
    }

    private Path topicIndexPath() {
        return root.resolve(METADATA_DIRECTORY).resolve(TOPIC_INDEX_FILE);
    }

    private Map<String, PublisherProgress> readProgress(String topicId) {
        Path path = progressPath(topicId);
        if (!Files.exists(path)) return Map.of();
        try {
            return JsonSupport.MAPPER.readValue(path.toFile(), PROGRESS_TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read topic log " + topicId, ex);
        }
    }

    private void writeProgress(String topicId, Map<String, PublisherProgress> values) {
        try {
            Set<String> topics = new TreeSet<>(topicIds());
            topics.add(PersistenceHex.require256(topicId, TOPIC_ID_FIELD));
            AtomicFiles.write(topicIndexPath(), JsonSupport.MAPPER.writeValueAsBytes(topics));
            AtomicFiles.write(progressPath(topicId), JsonSupport.MAPPER.writeValueAsBytes(values));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write topic log " + topicId, ex);
        }
    }
}
