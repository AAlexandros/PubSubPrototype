package org.pubsub.prototype.registry.cardano;

import com.fasterxml.jackson.databind.JsonNode;
import org.pubsub.prototype.registry.TopicState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CardanoCliUtxoParser {
    private final JsonCodec jsonCodec = new JsonCodec();
    private final TopicDatumCodec datumCodec = new TopicDatumCodec();
    private final AikenScriptDataCodec scriptDataCodec = new AikenScriptDataCodec();

    public List<CardanoScriptUtxo> parse(String json) {
        JsonNode root = jsonCodec.readTree(json, "cardano-cli UTxO JSON");
        List<CardanoScriptUtxo> utxos = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode utxo = field.getValue();
            String rawDatum = rawDatum(utxo);
            utxos.add(new CardanoScriptUtxo(
                    field.getKey(),
                    assets(utxo.path(CardanoRegistryNames.UtxoField.VALUE.value())),
                    decodeDatum(rawDatum),
                    rawDatum
            ));
        }
        return List.copyOf(utxos);
    }

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

    private String rawDatum(JsonNode utxo) {
        JsonNode datum = utxo.path(CardanoRegistryNames.UtxoField.INLINE_DATUM.value());
        if (datum.isMissingNode() || datum.isNull()) {
            datum = utxo.path(CardanoRegistryNames.UtxoField.INLINE_DATUM_JSON.value());
        }
        if (datum.isMissingNode() || datum.isNull()) {
            return "";
        }
        return jsonCodec.compact(datum, "inline datum");
    }

    private Optional<TopicState> decodeDatum(String rawDatum) {
        if (rawDatum.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(datumCodec.decode(rawDatum));
        } catch (IllegalArgumentException ex) {
            try {
                return Optional.of(scriptDataCodec.decodeDatum(rawDatum));
            } catch (IllegalArgumentException scriptDataEx) {
                return Optional.empty();
            }
        }
    }
}
