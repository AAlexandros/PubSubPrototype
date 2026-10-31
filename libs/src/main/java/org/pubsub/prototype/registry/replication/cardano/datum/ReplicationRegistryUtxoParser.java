package org.pubsub.prototype.registry.replication.cardano.datum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.pubsub.prototype.registry.RegistryDataException;
import org.pubsub.prototype.registry.RegistryIdentityException;
import org.pubsub.prototype.util.files.JsonSupport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Parses Cardano CLI UTxO JSON and decodes inline replication-registry datums. */
public final class ReplicationRegistryUtxoParser {
    private final ReplicationRegistryAikenCodec codec = new ReplicationRegistryAikenCodec();

    public List<ReplicationRegistryScriptUtxo> parse(String json) {
        return parse(json, null);
    }

    public List<ReplicationRegistryScriptUtxo> parse(String json, String expectedAddress) {
        try {
            JsonNode root = JsonSupport.MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new RegistryDataException("Cardano replication UTxO query must return a JSON object");
            }
            List<ReplicationRegistryScriptUtxo> values = new ArrayList<>();
            Iterator<java.util.Map.Entry<String, JsonNode>> entries = root.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (expectedAddress != null && !expectedAddress.equals(entry.getValue().path("address").asText())) {
                    throw new RegistryIdentityException("Unexpected replication registry address at " + entry.getKey());
                }
                JsonNode value = entry.getValue().path("value");
                if (!value.isObject()) {
                    throw new RegistryDataException("Invalid replication registry UTxO at " + entry.getKey());
                }
                long lovelace = value.path("lovelace").asLong(0);
                if (lovelace <= 0) {
                    throw new RegistryDataException("Invalid replication registry UTxO value at " + entry.getKey());
                }
                JsonNode datum = entry.getValue().path("inlineDatum");
                if (datum.isMissingNode() || datum.isNull()) datum = entry.getValue().path("inlineDatumJson");
                java.util.Optional<org.pubsub.prototype.registry.replication.ReplicationServerState> state =
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
            throw new RegistryDataException("Unable to decode Cardano CLI UTxO JSON", ex);
        }
    }
}
