package org.pubsub.prototype.registry.replication.cardano.datum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.pubsub.prototype.util.JsonSupport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Parses Cardano CLI UTxO JSON and decodes inline replication-registry datums. */
public final class ReplicationRegistryUtxoParser {
    private final ReplicationRegistryAikenCodec codec = new ReplicationRegistryAikenCodec();

    public List<ReplicationRegistryScriptUtxo> parse(String json) {
        try {
            JsonNode root = JsonSupport.MAPPER.readTree(json);
            List<ReplicationRegistryScriptUtxo> values = new ArrayList<>();
            Iterator<java.util.Map.Entry<String, JsonNode>> entries = root.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                JsonNode value = entry.getValue().path("value");
                long lovelace = value.path("lovelace").asLong(0);
                JsonNode datum = entry.getValue().path("inlineDatum");
                if (datum.isMissingNode() || datum.isNull()) datum = entry.getValue().path("inlineDatumJson");
                java.util.Optional<org.pubsub.prototype.persistence.ReplicationServerState> state =
                        java.util.Optional.empty();
                if (!datum.isMissingNode() && !datum.isNull()) {
                    try {
                        state = java.util.Optional.of(codec.decodeDatum(datum));
                    } catch (RuntimeException ignored) {
                        // Other datums at the same address do not represent replication registrations.
                    }
                }
                values.add(new ReplicationRegistryScriptUtxo(entry.getKey(), lovelace, state));
            }
            return List.copyOf(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to decode Cardano CLI UTxO JSON", ex);
        }
    }
}
