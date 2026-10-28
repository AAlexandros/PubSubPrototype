package org.pubsub.prototype.persistence.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.util.files.AtomicFiles;
import org.pubsub.prototype.util.HexUtils;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.pubsub.prototype.constants.PersistenceConstants.PUBLISHER_KEY_ID_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.LAST_DELIVERED_SEQUENCE_NUMBER_FIELD;
import static org.pubsub.prototype.constants.PersistenceConstants.LAST_DELIVERED_TIMESTAMP_FIELD;
import static org.pubsub.prototype.util.validation.Validation.start;

/** Stores the highest accepted sequence and timestamp per topic and publisher on local disk. */
public final class DeliveryStateStore {
    private static final TypeReference<Map<String, Map<String, DeliveryProgress>>> TYPE = new TypeReference<>() { };
    private final Path file;
    private Map<String, Map<String, DeliveryProgress>> state;

    public DeliveryStateStore(Path file) {
        this.file = file;
        this.state = load();
    }

    public synchronized DeliveryProgress progress(String topicId, String publisherKeyId) {
        topicId = HexUtils.normalizeSha256(topicId, TOPIC_ID_FIELD);
        publisherKeyId = HexUtils.normalizeSha256(publisherKeyId, PUBLISHER_KEY_ID_FIELD);
        return state.getOrDefault(topicId, Map.of()).getOrDefault(publisherKeyId, DeliveryProgress.empty());
    }

    /**
     * Saves the sequence and timestamp only if the sequence is higher than the stored value.
     * Lower sequence gaps are not tracked.
     *
     * @return whether the stored values changed
     */
    public synchronized boolean recordLatestAcceptedSequence(
            String topicId, String publisherKeyId, long sequenceNumber, long timestamp) {
        topicId = HexUtils.normalizeSha256(topicId, TOPIC_ID_FIELD);
        publisherKeyId = HexUtils.normalizeSha256(publisherKeyId, PUBLISHER_KEY_ID_FIELD);
        DeliveryProgress previous = progress(topicId, publisherKeyId);
        if (sequenceNumber <= previous.lastDeliveredSequenceNumber()) return false;
        Map<String, Map<String, DeliveryProgress>> updated = deepCopy(state);
        updated.computeIfAbsent(topicId, ignored -> new LinkedHashMap<>())
                .put(publisherKeyId, new DeliveryProgress(sequenceNumber, timestamp));
        write(updated);
        state = updated;
        return true;
    }

    public synchronized Map<String, Map<String, DeliveryProgress>> snapshot() {
        return deepCopy(state);
    }

    public synchronized long latestDeliveredTimestamp(String topicId) {
        topicId = HexUtils.normalizeSha256(topicId, TOPIC_ID_FIELD);
        return state.getOrDefault(topicId, Map.of()).values().stream()
                .mapToLong(DeliveryProgress::lastDeliveredTimestamp)
                .max().orElse(0);
    }

    private Map<String, Map<String, DeliveryProgress>> load() {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            return deepCopy(JsonSupport.MAPPER.readValue(file.toFile(), TYPE));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read delivery state " + file, ex);
        }
    }

    private void write(Map<String, Map<String, DeliveryProgress>> value) {
        try {
            AtomicFiles.write(file, JsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist delivery state " + file, ex);
        }
    }

    private static Map<String, Map<String, DeliveryProgress>> deepCopy(Map<String, Map<String, DeliveryProgress>> source) {
        Map<String, Map<String, DeliveryProgress>> result = new LinkedHashMap<>();
        source.forEach((topic, publishers) -> result.put(topic, new LinkedHashMap<>(publishers)));
        return result;
    }

    /** The highest sequence this node accepted for one topic and publisher, with its timestamp. */
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
}
