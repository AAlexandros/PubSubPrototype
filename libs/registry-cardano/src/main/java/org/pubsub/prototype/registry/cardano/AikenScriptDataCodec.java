package org.pubsub.prototype.registry.cardano;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.registry.TopicState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

final class AikenScriptDataCodec {
    private static final HexFormat HEX = HexFormat.of();
    private final JsonCodec jsonCodec = new JsonCodec();
    private final ObjectMapper mapper = jsonCodec.mapper();

    String encodeDatum(TopicState topic, Function<String, String> keyHashResolver) {
        return encodeDatum(
                topic.topicId(),
                topic.name(),
                topic.owners(),
                topic.admins(),
                topic.publishers(),
                topic.replicationFactor(),
                topic.retentionPeriod(),
                topic.active(),
                keyHashResolver
        );
    }

    String encodeDatum(
            TopicId topicId,
            String name,
            List<String> owners,
            List<String> admins,
            List<String> publishers,
            int replicationFactor,
            long retentionPeriod,
            boolean active,
            Function<String, String> keyHashResolver
    ) {
        ObjectNode datum = constructor(0);
        ArrayNode fields = datum.withArray(CardanoRegistryNames.ScriptDataField.FIELDS.value());
        fields.add(bytes(topicId.value()));
        fields.add(bytes(HEX.formatHex(name.getBytes(StandardCharsets.UTF_8))));
        fields.add(bytesList(owners, keyHashResolver));
        fields.add(bytesList(admins, keyHashResolver));
        fields.add(bytesList(publishers, keyHashResolver));
        fields.add(integer(replicationFactor));
        fields.add(integer(retentionPeriod));
        fields.add(constructor(active ? 1 : 0));
        return pretty(datum);
    }

    TopicState decodeDatum(String json) {
        try {
            JsonNode root = jsonCodec.readTree(json, "Aiken topic datum");
            ArrayNode fields = fields(root, 0);
            return new TopicState(
                    new TopicId(bytesValue(fields.get(0))),
                    new String(HEX.parseHex(bytesValue(fields.get(1))), StandardCharsets.UTF_8),
                    decodeBytesList(fields.get(2)),
                    decodeBytesList(fields.get(3)),
                    decodeBytesList(fields.get(4)),
                    intValue(fields.get(5)),
                    longValue(fields.get(6)),
                    constructorIndex(fields.get(7)) == 1
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to decode Aiken topic datum", ex);
        }
    }

    String encodeMintRedeemer(String creationRef, String tokenName, TopicId topicId) {
        ObjectNode redeemer = constructor(0);
        ArrayNode fields = redeemer.withArray(CardanoRegistryNames.ScriptDataField.FIELDS.value());
        fields.add(outputReference(creationRef));
        fields.add(bytes(tokenName));
        fields.add(bytes(topicId.value()));
        return pretty(redeemer);
    }

    String encodeTopicRedeemer(TopicMutation mutation, Function<String, String> keyHashResolver) {
        return pretty(switch (mutation.operation()) {
            case DELETE_TOPIC -> constructor(0);
            case ADD_OWNER -> oneFieldConstructor(1, bytes(keyHashResolver.apply(mutation.value())));
            case REMOVE_OWNER -> oneFieldConstructor(2, bytes(keyHashResolver.apply(mutation.value())));
            case ADD_ADMIN -> oneFieldConstructor(3, bytes(keyHashResolver.apply(mutation.value())));
            case REMOVE_ADMIN -> oneFieldConstructor(4, bytes(keyHashResolver.apply(mutation.value())));
            case ADD_PUBLISHER -> oneFieldConstructor(5, bytes(keyHashResolver.apply(mutation.value())));
            case REMOVE_PUBLISHER -> oneFieldConstructor(6, bytes(keyHashResolver.apply(mutation.value())));
            case SET_REPLICATION_FACTOR -> oneFieldConstructor(7, integer(Integer.parseInt(mutation.value())));
            case SET_RETENTION_PERIOD -> oneFieldConstructor(8, integer(Long.parseLong(mutation.value())));
        });
    }

    private ObjectNode outputReference(String ref) {
        String[] parts = ref.split("#", 2);
        if (parts.length != 2) {
            throw new RegistryConflictException("Invalid UTxO reference: " + ref);
        }
        ObjectNode outputReference = constructor(0);
        ArrayNode fields = outputReference.withArray(CardanoRegistryNames.ScriptDataField.FIELDS.value());
        fields.add(bytes(parts[0]));
        fields.add(integer(Long.parseLong(parts[1])));
        return outputReference;
    }

    private ObjectNode bytesList(List<String> values, Function<String, String> keyHashResolver) {
        ArrayNode list = mapper.createArrayNode();
        for (String value : values) {
            list.add(bytes(keyHashResolver.apply(value)));
        }
        return listNode(list);
    }

    private ObjectNode listNode(ArrayNode values) {
        ObjectNode node = mapper.createObjectNode();
        node.set(CardanoRegistryNames.ScriptDataField.LIST.value(), values);
        return node;
    }

    private ObjectNode oneFieldConstructor(int index, JsonNode value) {
        ObjectNode node = constructor(index);
        node.withArray(CardanoRegistryNames.ScriptDataField.FIELDS.value()).add(value);
        return node;
    }

    private ObjectNode constructor(int index) {
        ObjectNode node = mapper.createObjectNode();
        node.put(CardanoRegistryNames.ScriptDataField.CONSTRUCTOR.value(), index);
        node.set(CardanoRegistryNames.ScriptDataField.FIELDS.value(), mapper.createArrayNode());
        return node;
    }

    private ObjectNode bytes(String hex) {
        if (!hex.matches("[0-9a-fA-F]*") || hex.length() % 2 != 0) {
            throw new RegistryConflictException("Expected hex bytes for script data: " + hex);
        }
        ObjectNode node = mapper.createObjectNode();
        node.put(CardanoRegistryNames.ScriptDataField.BYTES.value(), hex.toLowerCase());
        return node;
    }

    private ObjectNode integer(long value) {
        ObjectNode node = mapper.createObjectNode();
        node.put(CardanoRegistryNames.ScriptDataField.INT.value(), value);
        return node;
    }

    private ArrayNode fields(JsonNode node, int constructor) {
        if (constructorIndex(node) != constructor || !node.path(CardanoRegistryNames.ScriptDataField.FIELDS.value()).isArray()) {
            throw new IllegalArgumentException("Unexpected constructor shape");
        }
        return (ArrayNode) node.path(CardanoRegistryNames.ScriptDataField.FIELDS.value());
    }

    private int constructorIndex(JsonNode node) {
        return node.path(CardanoRegistryNames.ScriptDataField.CONSTRUCTOR.value()).asInt(-1);
    }

    private String bytesValue(JsonNode node) {
        String value = node.path(CardanoRegistryNames.ScriptDataField.BYTES.value()).asText("");
        if (!value.matches("[0-9a-fA-F]*") || value.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid bytes value");
        }
        return value.toLowerCase();
    }

    private int intValue(JsonNode node) {
        return Math.toIntExact(longValue(node));
    }

    private long longValue(JsonNode node) {
        if (!node.path(CardanoRegistryNames.ScriptDataField.INT.value()).canConvertToLong()) {
            throw new IllegalArgumentException("Invalid integer value");
        }
        return node.path(CardanoRegistryNames.ScriptDataField.INT.value()).longValue();
    }

    private List<String> decodeBytesList(JsonNode node) {
        if (!node.path(CardanoRegistryNames.ScriptDataField.LIST.value()).isArray()) {
            throw new IllegalArgumentException("Invalid list value");
        }
        List<String> values = new ArrayList<>();
        node.path(CardanoRegistryNames.ScriptDataField.LIST.value()).forEach(value -> values.add(bytesValue(value)));
        return List.copyOf(values);
    }

    private String pretty(JsonNode node) {
        return jsonCodec.pretty(node, "Aiken script data");
    }
}
