package org.pubsub.prototype.registry.topic.cardano.datum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pubsub.prototype.registry.TopicState;
import org.pubsub.prototype.util.JsonSupport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CardanoCliUtxoParser {
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER;
    private static final String VALUE_FIELD = "value";
    private static final String INLINE_DATUM_FIELD = "inlineDatum";
    private static final String INLINE_DATUM_JSON_FIELD = "inlineDatumJson";
    private final AikenScriptDataCodec scriptDataCodec = new AikenScriptDataCodec();

    /**
     * Parses {@code cardano-cli query utxo --output-json} output, and constructs a list of {@link CardanoScriptUtxo} objects,
     * using jackson, and {@link AikenScriptDataCodec} for datum decoding.
     * Each top-level entry is a UTxO keyed by {@code transactionHash#index}.
     */
    public List<CardanoScriptUtxo> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to decode cardano-cli UTxO JSON", ex);
        }
        List<CardanoScriptUtxo> utxos = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        // Iterate over the keys of the top level entities, which are the UTxO references.
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode utxo = field.getValue();
            Optional<String> rawDatum = rawDatum(utxo);
            utxos.add(new CardanoScriptUtxo(
                    field.getKey(),
                    assets(utxo.path(VALUE_FIELD)),
                    decodeDatum(rawDatum),
                    rawDatum
            ));
        }
        return List.copyOf(utxos);
    }

    /**
     * Flattens CLI value JSON into asset quantities.
     * Direct values use their asset name; policy tokens use {@code policyId.tokenName}.
     */
    private Map<String, Long> assets(JsonNode value) {
        Map<String, Long> assets = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            JsonNode quantity = entry.getValue();
            if (quantity.isNumber()) {
                assets.put(entry.getKey(), quantity.longValue());
            } else if (quantity.isObject()) {
                quantity.fields().forEachRemaining(token ->
                        assets.put(entry.getKey() + "." + token.getKey(), token.getValue().longValue()));
            }
        });
        return assets;
    }

    /**
     * Reads the inline datum from either supported CLI field name.
     * {@code inlineDatum} takes precedence over {@code inlineDatumJson}.
     */
    private Optional<String> rawDatum(JsonNode utxo) {
        JsonNode datum = utxo.path(INLINE_DATUM_FIELD);
        if (datum.isMissingNode() || datum.isNull()) {
            datum = utxo.path(INLINE_DATUM_JSON_FIELD);
        }
        if (datum.isMissingNode() || datum.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.writeValueAsString(datum));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode inline datum", ex);
        }
    }

    private Optional<TopicState> decodeDatum(Optional<String> rawDatum) {
        if (rawDatum.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(scriptDataCodec.decodeDatum(rawDatum.orElseThrow()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
