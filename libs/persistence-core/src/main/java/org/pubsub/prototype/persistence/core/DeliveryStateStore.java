package org.pubsub.prototype.persistence.core;

import com.fasterxml.jackson.core.type.TypeReference;
import org.pubsub.prototype.persistence.DeliveryProgress;
import org.pubsub.prototype.persistence.PersistenceHex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeliveryStateStore {
    private static final TypeReference<Map<String, Map<String, DeliveryProgress>>> TYPE = new TypeReference<>() { };
    private final Path file;
    private Map<String, Map<String, DeliveryProgress>> state;

    public DeliveryStateStore(Path file) {
        this.file = file;
        this.state = load();
    }

    public synchronized DeliveryProgress progress(String topicId, String publisherKeyId) {
        PersistenceHex.require256(topicId, "topicId");
        PersistenceHex.require256(publisherKeyId, "publisherKeyId");
        return state.getOrDefault(topicId, Map.of()).getOrDefault(publisherKeyId, DeliveryProgress.empty());
    }

    public synchronized boolean recordDelivered(String topicId, String publisherKeyId, long sequenceNumber, long timestamp) {
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
        PersistenceHex.require256(topicId, "topicId");
        return state.getOrDefault(topicId, Map.of()).values().stream()
                .mapToLong(DeliveryProgress::lastDeliveredTimestamp)
                .max().orElse(0);
    }

    private Map<String, Map<String, DeliveryProgress>> load() {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            return deepCopy(PersistenceJson.MAPPER.readValue(file.toFile(), TYPE));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read delivery state " + file, ex);
        }
    }

    private void write(Map<String, Map<String, DeliveryProgress>> value) {
        try {
            AtomicFiles.write(file, PersistenceJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist delivery state " + file, ex);
        }
    }

    private static Map<String, Map<String, DeliveryProgress>> deepCopy(Map<String, Map<String, DeliveryProgress>> source) {
        Map<String, Map<String, DeliveryProgress>> result = new LinkedHashMap<>();
        source.forEach((topic, publishers) -> result.put(topic, new LinkedHashMap<>(publishers)));
        return result;
    }
}
