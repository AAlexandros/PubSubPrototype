package org.pubsub.prototype.persistence.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.constants.PersistenceConstants;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.TopicStateProvider;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.core.EpochProvider;
import org.pubsub.prototype.persistence.core.EventKeys;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.util.HexUtils;
import org.pubsub.prototype.util.files.AtomicFiles;
import org.pubsub.prototype.util.files.JsonSupport;
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

import static org.pubsub.prototype.constants.EventConstants.EVENT_ID_FIELD;
import static org.pubsub.prototype.util.validation.Validators.require;

/**
 * Stores one replication server's local event replicas and publisher progress
 * on disk.
 * Events are JSON files keyed by {@link EventKeys#eventKey(EventEnvelope)};
 * each topic's
 * publisher progress is a separate JSON file keyed by its topic ID. Metadata
 * records
 * the server identity and indexes local topic logs for replica maintenance.
 *
 * Event expiry is recalculated from the current topic retention period whenever
 * a replica is stored or read.
 */
public final class FileEventStore {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(FileEventStore.class);

    private static final TypeReference<Map<String, PublisherProgress>> PROGRESS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Set<String>> TOPIC_INDEX_TYPE = new TypeReference<>() {
    };

    // Paths for storing the events and topic logs within the file-based event store
    private final Path root;
    private final Path events;
    private final Path topicLogs;

    private final Clock clock;

    private final EpochProvider epochProvider;
    private final TopicStateProvider topicStateProvider;

    /**
     * Creates the event, topic-log, and metadata directories beneath {@code root}.
     * The topic provider supplies the current retention period for every event
     * write, read, and expiration scan.
     */
    public FileEventStore(Path root, Clock clock, EpochProvider epochs, TopicStateProvider topics) {
        this.root = root;
        this.events = root.resolve(PersistenceConstants.EVENTS_DIRECTORY);
        this.topicLogs = root.resolve(PersistenceConstants.TOPIC_LOGS_DIRECTORY);
        this.clock = clock;
        this.epochProvider = epochs;
        this.topicStateProvider = topics;

        // Initialize the directories for events, topic logs, and metadata
        try {
            Files.createDirectories(events);
            Files.createDirectories(topicLogs);
            Files.createDirectories(root.resolve(PersistenceConstants.METADATA_DIRECTORY));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize persistence store " + root, ex);
        }
    }

    /**
     * Builds a record with the current time and epoch; does not write it to disk.
     */
    public StoredEvent createEventRecord(EventEnvelope envelope, TopicState topic) {
        long epoch = epochProvider.currentEpoch();
        return new StoredEvent(EventKeys.eventKey(envelope), envelope, Instant.now(clock), topic.retentionPeriod(),
                epoch,
                Math.addExact(epoch, topic.retentionPeriod()));
    }

    /**
     * Saves a replica using the topic's current retention period rather than the
     * period supplied by the sender. Checks that its key matches its event
     * envelope.
     * Keeps an existing unexpired record when the incoming record's storage
     * time is equal or later; otherwise replaces it with the earlier record.
     *
     * @return the record held locally after the write
     */
    public synchronized StoredEvent storeEventReplica(StoredEvent record) {
        require(EventKeys.eventKey(record.eventEnvelope()).equals(record.eventKey()),
                PersistenceConstants.EVENT_KEY_FIELD + " does not match " + PersistenceConstants.EVENT_ENVELOPE_FIELD);
        long currentEpoch = epochProvider.currentEpoch();
        require(record.storedAtEpoch() <= currentEpoch,
                PersistenceConstants.STORED_AT_EPOCH_FIELD + " is in the future");
        StoredEvent current = recordWithCurrentTopicRetention(record);
        require(current.expiresAfterEpoch() > currentEpoch,
                PersistenceConstants.EXPIRES_AFTER_EPOCH_FIELD + " has passed");
        Optional<StoredEvent> existing = findUnexpiredEventOrDeleteExpiredFile(record.eventKey());
        StoredEvent selected = existing.filter(value -> !current.storedAt().isBefore(value.storedAt())).orElse(current);
        if (existing.isPresent() && selected == existing.orElseThrow())
            return selected;
        try {
            AtomicFiles.write(eventPath(record.eventKey()), JsonSupport.MAPPER.writeValueAsBytes(selected));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to store event replica " + record.eventKey(), ex);
        }
        return selected;
    }

    /**
     * Reads one local event using the current topic retention period. Updates
     * its stored metadata when the period changes, or deletes its file when
     * the event has expired.
     */
    public synchronized Optional<StoredEvent> findUnexpiredEventOrDeleteExpiredFile(String eventKey) {
        Path path = eventPath(eventKey);
        if (!Files.exists(path))
            return Optional.empty();
        try {
            StoredEvent stored = JsonSupport.MAPPER.readValue(path.toFile(), StoredEvent.class);
            StoredEvent event = recordWithCurrentTopicRetention(stored);
            if (event.expiresAfterEpoch() <= epochProvider.currentEpoch()) {
                Files.deleteIfExists(path);
                LOG.info("EVENT_EXPIRED " + PersistenceConstants.EVENT_KEY_FIELD + "={} "
                        + EVENT_ID_FIELD + "={} "
                        + PersistenceConstants.TOPIC_ID_FIELD + "={} "
                        + PersistenceConstants.PUBLISHER_KEY_ID_FIELD + "={} "
                        + PersistenceConstants.SEQUENCE_NUMBER_FIELD + "={}",
                        event.eventKey(), event.eventEnvelope().eventId(), event.eventEnvelope().topicId(),
                        EventCrypto.publisherKeyId(event.eventEnvelope()), event.eventEnvelope().sequenceNumber());
                return Optional.empty();
            }
            if (event.topicRetentionPeriod() != stored.topicRetentionPeriod())
                AtomicFiles.write(path, JsonSupport.MAPPER.writeValueAsBytes(event));
            return Optional.of(event);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read event " + eventKey, ex);
        }
    }

    /**
     * Checks whether the event file exists without reading it or applying
     * expiration.
     */
    public synchronized boolean containsRaw(String eventKey) {
        return Files.exists(eventPath(eventKey));
    }

    /**
     * Updates one publisher's entry in a topic log when its sequence is higher,
     * or its sequence is equal and its timestamp is later.
     *
     * @return whether the entry changed
     */
    public synchronized boolean updateProgress(String topicId, PublisherProgress progress) {
        require(topicId.equals(progress.topicId()), "publisher progress topic does not match log");
        Map<String, PublisherProgress> values = new LinkedHashMap<>(readProgress(topicId));
        PublisherProgress previous = values.get(progress.publisherKeyId());
        if (previous != null && (previous.latestSequenceNumber() > progress.latestSequenceNumber()
                || (previous.latestSequenceNumber() == progress.latestSequenceNumber()
                        && previous.latestTimestamp() >= progress.latestTimestamp())))
            return false;
        values.put(progress.publisherKeyId(), progress);
        writeProgress(topicId, values);
        return true;
    }

    /**
     * Applies each progress entry using the same ordering rule as
     * {@link #updateProgress}.
     */
    public synchronized void mergeProgress(String topicId, List<PublisherProgress> progress) {
        progress.forEach(value -> updateProgress(topicId, value));
    }

    /**
     * Returns this server's progress entries for a topic, sorted by publisher key
     * ID.
     */
    public synchronized List<PublisherProgress> publisherProgress(String topicId) {
        return readProgress(topicId).values().stream()
                .sorted(Comparator.comparing(PublisherProgress::publisherKeyId)).toList();
    }

    /**
     * Scans the event directory and removes expired event files. Each file is
     * checked against the topic's current retention period.
     *
     * @return number of event files removed during this scan
     */
    public synchronized int deleteExpiredEventFiles() {
        if (!Files.exists(events))
            return 0;
        int removed = 0;
        try (var paths = Files.list(events)) {
            for (Path path : paths.filter(value -> value.getFileName().toString()
                    .endsWith(PersistenceConstants.JSON_EXTENSION)).toList()) {
                String key = path.getFileName().toString()
                        .replaceFirst(PersistenceConstants.JSON_EXTENSION_PATTERN, "");
                if (findUnexpiredEventOrDeleteExpiredFile(key).isEmpty() && !Files.exists(path))
                    removed++;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to clean expired events", ex);
        }
        return removed;
    }

    /**
     * Lists local event filenames as keys, including records not yet checked for
     * expiration.
     */
    public synchronized List<String> eventKeys() {
        try (var paths = Files.list(events)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(PersistenceConstants.JSON_EXTENSION))
                    .map(path -> path.getFileName().toString()
                            .replaceFirst(PersistenceConstants.JSON_EXTENSION_PATTERN, ""))
                    .sorted().toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inventory events", ex);
        }
    }

    /**
     * Returns unexpired local records sorted by event key. Expired files are
     * deleted as each record is read.
     */
    public synchronized List<StoredEvent> eventRecords() {
        List<StoredEvent> records = new ArrayList<>();
        for (String eventKey : eventKeys())
            findUnexpiredEventOrDeleteExpiredFile(eventKey).ifPresent(records::add);
        return records.stream().sorted(Comparator.comparing(StoredEvent::eventKey)).toList();
    }

    /**
     * Applies the latest chain-observed topic policy to a replica's storage epoch.
     */
    private StoredEvent recordWithCurrentTopicRetention(StoredEvent record) {
        TopicState topic = topicStateProvider.findTopicState(new TopicId(record.eventEnvelope().topicId()))
                .orElseThrow(() -> new IllegalStateException("unknown topic for stored event"));
        long period = topic.retentionPeriod();
        long expires = Math.addExact(record.storedAtEpoch(), period);
        if (period == record.topicRetentionPeriod() && expires == record.expiresAfterEpoch())
            return record;
        return new StoredEvent(record.eventKey(), record.eventEnvelope(), record.storedAt(), period,
                record.storedAtEpoch(), expires);
    }

    /**
     * Deletes a local event file if present, without changing publisher progress.
     */
    public synchronized boolean deleteEvent(String eventKey) {
        try {
            return Files.deleteIfExists(eventPath(eventKey));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to delete event " + eventKey, ex);
        }
    }

    /**
     * Returns indexed topic IDs whose local progress files still exist, in sorted
     * order.
     */
    public synchronized List<String> topicIds() {
        Path path = topicIndexPath();
        if (!Files.exists(path))
            return List.of();
        try {
            return JsonSupport.MAPPER.readValue(path.toFile(), TOPIC_INDEX_TYPE).stream()
                    .filter(this::hasTopicLog).sorted().toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read topic-log index", ex);
        }
    }

    /** Checks whether this server has a progress file for the topic. */
    public synchronized boolean hasTopicLog(String topicId) {
        return Files.exists(progressPath(topicId));
    }

    /** Deletes a topic's progress file and removes its ID from the local index. */
    public synchronized boolean deleteTopicLog(String topicId) {
        try {
            boolean removed = Files.deleteIfExists(progressPath(topicId));
            if (removed) {
                Set<String> topics = new TreeSet<>(topicIds());
                topics.remove(HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD));
                AtomicFiles.write(topicIndexPath(), JsonSupport.MAPPER.writeValueAsBytes(topics));
            }
            return removed;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to delete topic log " + topicId, ex);
        }
    }

    /**
     * Binds this storage directory to one replication-server identity.
     *
     * <p>
     * On first use, persists {@code serverId} in {@code metadata/server-id.txt}. On
     * later starts,
     * verifies that the configured ID matches the persisted ID, preventing a
     * different server from
     * accidentally reusing this store. Throws if the directory is already bound to
     * another ID.
     *
     * @param serverId configured identity of the replication server using this
     *                 store
     */
    public synchronized void ensureServerIdentity(String serverId) {
        String normalized = HexUtils.normalizeSha256(serverId, PersistenceConstants.SERVER_ID_FIELD);
        Path path = root.resolve(PersistenceConstants.METADATA_DIRECTORY).resolve(PersistenceConstants.SERVER_ID_FILE);
        try {
            if (Files.exists(path)) {
                String persisted = Files.readString(path).trim();
                if (!normalized.equals(persisted)) {
                    throw new IllegalStateException("storage belongs to replication server " + persisted);
                }
                return;
            }
            AtomicFiles.write(path,
                    (normalized + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist replication server identity", ex);
        }
    }

    /** Returns the root directory for this server's local store. */
    public Path root() {
        return root;
    }

    /**
     * Resolves an event's JSON file beneath the {@code events} directory. The event
     * key is
     * validated and normalized as a 256-bit hexadecimal value before it becomes a
     * filename.
     * For example, with root {@code /var/lib/pubsub}, an abbreviated key produces
     * {@code /var/lib/pubsub/events/0123abcd...cdef.json}.
     *
     * @param eventKey SHA-256 event key
     * @return path to the event JSON file
     */
    private Path eventPath(String eventKey) {
        return events.resolve(HexUtils.normalizeSha256(eventKey, PersistenceConstants.EVENT_KEY_FIELD)
                + PersistenceConstants.JSON_EXTENSION);
    }

    /**
     * Resolves a topic's publisher-progress JSON file beneath the
     * {@code topic-logs} directory.
     * The topic ID itself is the topic-log key. For example, with root
     * {@code /var/lib/pubsub}, an abbreviated topic ID produces
     * {@code /var/lib/pubsub/topic-logs/89abcdef...0123.json}.
     * 
     * If a server is chosen to store progress logs for a topic, it will store the
     * JSON files for all publishers of that topic.
     *
     * @param topicId unique 256-bit topic ID
     * @return path to the topic's publisher-progress JSON file
     */
    private Path progressPath(String topicId) {
        return topicLogs.resolve(EventKeys.topicLogKey(topicId) + PersistenceConstants.JSON_EXTENSION);
    }

    /**
     * Resolves the JSON index of local topic logs used by replica maintenance for
     * inventory
     * and repair. For example, with root {@code /var/lib/pubsub}, the path is
     * {@code /var/lib/pubsub/metadata/topic-index.json}.
     *
     * @return path to the topic index JSON file
     */
    private Path topicIndexPath() {
        return root.resolve(PersistenceConstants.METADATA_DIRECTORY).resolve(PersistenceConstants.TOPIC_INDEX_FILE);
    }

    private Map<String, PublisherProgress> readProgress(String topicId) {
        Path path = progressPath(topicId);
        if (!Files.exists(path))
            return Map.of();
        try {
            Map<String, PublisherProgress> values = JsonSupport.MAPPER.readValue(path.toFile(), PROGRESS_TYPE);
            values.forEach((publisherId, progress) -> require(topicId.equals(progress.topicId())
                    && publisherId.equals(progress.publisherKeyId()), "topic log entry does not match its key"));
            return values;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read topic log " + topicId, ex);
        }
    }

    private void writeProgress(String topicId, Map<String, PublisherProgress> values) {
        try {
            Set<String> topics = new TreeSet<>(topicIds());
            topics.add(HexUtils.normalizeSha256(topicId, PersistenceConstants.TOPIC_ID_FIELD));
            AtomicFiles.write(topicIndexPath(), JsonSupport.MAPPER.writeValueAsBytes(topics));
            AtomicFiles.write(progressPath(topicId), JsonSupport.MAPPER.writeValueAsBytes(values));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write topic log " + topicId, ex);
        }
    }
}
