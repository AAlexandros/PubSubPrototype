package org.pubsub.prototype.registry.topic.cardano.datum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.pubsub.prototype.constants.CryptoConstants;
import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.topic.TopicId;
import org.pubsub.prototype.registry.topic.TopicState;
import org.pubsub.prototype.registry.topic.cardano.mutation.TopicMutation;
import org.pubsub.prototype.util.files.JsonSupport;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Converts Topic Registry datums and redeemers to Aiken Plutus-data JSON.
 * Constructor indexes and field order mirror the Aiken contract types.
 */
public final class AikenScriptDataCodec {
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER;
    private static final String CONSTRUCTOR_FIELD = "constructor";
    private static final String FIELDS_FIELD = "fields";
    private static final String BYTES_FIELD = "bytes";
    private static final String INTEGER_FIELD = "int";
    private static final String LIST_FIELD = "list";

    // Fields for TopicState datum
    private static final int TOPIC_STATE_FIELD_COUNT = 8;
    private static final int TOPIC_ID_FIELD = 0;
    private static final int NAME_FIELD = 1;
    private static final int OWNERS_FIELD = 2;
    private static final int ADMINS_FIELD = 3;
    private static final int PUBLISHERS_FIELD = 4;
    private static final int REPLICATION_FACTOR_FIELD = 5;
    private static final int RETENTION_PERIOD_FIELD = 6;
    private static final int ACTIVE_FIELD = 7;
    private static final int FALSE_CONSTRUCTOR = 0;
    private static final int TRUE_CONSTRUCTOR = 1;

    /** Encodes a topic state as the inline datum stored at the registry script. */
    public String encodeDatum(TopicState topic, Function<String, String> keyHashResolver) {
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

/**
 * Encodes explicit topic fields as a datum.
 */
    public String encodeDatum(
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
        ArrayNode fields = datum.withArray(FIELDS_FIELD);
        fields.add(bytes(topicId.value()));
        fields.add(bytes(HEX.formatHex(name.getBytes(StandardCharsets.UTF_8))));
        fields.add(bytesList(owners, keyHashResolver));
        fields.add(bytesList(admins, keyHashResolver));
        fields.add(rawBytesList(publishers));
        fields.add(integer(replicationFactor));
        fields.add(integer(retentionPeriod));
        fields.add(constructor(active ? 1 : 0));
        return pretty(datum);
    }

    /** Decodes an Aiken topic datum returned by {@code cardano-cli}. */
    public TopicState decodeDatum(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            ArrayNode fields = fields(root, 0);
            if (fields.size() != TOPIC_STATE_FIELD_COUNT) {
                throw new IllegalArgumentException("Unexpected topic datum field count");
            }
            JsonNode activeField = fields.get(ACTIVE_FIELD);
            int active = constructorIndex(activeField);
            if ((active != FALSE_CONSTRUCTOR && active != TRUE_CONSTRUCTOR)
                    || !activeField.path(FIELDS_FIELD).isArray()
                    || activeField.path(FIELDS_FIELD).size() != 0) {
                throw new IllegalArgumentException("Invalid topic datum active flag");
            }
            return new TopicState(
                    new TopicId(bytesValue(fields.get(TOPIC_ID_FIELD))),
                    StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(HEX.parseHex(bytesValue(fields.get(NAME_FIELD))))).toString(),
                    decodeBytesList(fields.get(OWNERS_FIELD)),
                    decodeBytesList(fields.get(ADMINS_FIELD)),
                    decodeBytesList(fields.get(PUBLISHERS_FIELD)),
                    intValue(fields.get(REPLICATION_FACTOR_FIELD)),
                    longValue(fields.get(RETENTION_PERIOD_FIELD)),
                    active == TRUE_CONSTRUCTOR
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to decode Aiken topic datum", ex);
        }
    }

    /** Encodes the mint-policy redeemer for a new topic token. */
    public String encodeMintRedeemer(String creationRef, String tokenName, TopicId topicId) {
        ObjectNode redeemer = constructor(0);
        ArrayNode fields = redeemer.withArray(FIELDS_FIELD);
        fields.add(outputReference(creationRef));
        fields.add(bytes(tokenName));
        fields.add(bytes(topicId.value()));
        return pretty(redeemer);
    }

    /** Encodes the spend-validator redeemer for a topic mutation. */
    public String encodeTopicRedeemer(TopicMutation mutation, Function<String, String> keyHashResolver) {
        return pretty(switch (mutation.operation()) {
            case DELETE_TOPIC -> constructor(0);
            case ADD_OWNER -> oneFieldConstructor(1, bytes(keyHashResolver.apply(mutation.value())));
            case REMOVE_OWNER -> oneFieldConstructor(2, bytes(keyHashResolver.apply(mutation.value())));
            case ADD_ADMIN -> oneFieldConstructor(3, bytes(keyHashResolver.apply(mutation.value())));
            case REMOVE_ADMIN -> oneFieldConstructor(4, bytes(keyHashResolver.apply(mutation.value())));
            case ADD_PUBLISHER -> oneFieldConstructor(5, bytes(mutation.value()));
            case REMOVE_PUBLISHER -> oneFieldConstructor(6, bytes(mutation.value()));
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
        ArrayNode fields = outputReference.withArray(FIELDS_FIELD);
        fields.add(bytes(parts[0]));
        fields.add(integer(Long.parseLong(parts[1])));
        return outputReference;
    }

    private ObjectNode bytesList(List<String> values, Function<String, String> keyHashResolver) {
        ArrayNode list = MAPPER.createArrayNode();
        for (String value : values) {
            list.add(bytes(keyHashResolver.apply(value)));
        }
        return listNode(list);
    }

    private ObjectNode rawBytesList(List<String> values) {
        ArrayNode list = MAPPER.createArrayNode();
        for (String value : values) {
            list.add(bytes(value));
        }
        return listNode(list);
    }

    private ObjectNode listNode(ArrayNode values) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set(LIST_FIELD, values);
        return node;
    }

    private ObjectNode oneFieldConstructor(int index, JsonNode value) {
        ObjectNode node = constructor(index);
        node.withArray(FIELDS_FIELD).add(value);
        return node;
    }

    private ObjectNode constructor(int index) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(CONSTRUCTOR_FIELD, index);
        node.set(FIELDS_FIELD, MAPPER.createArrayNode());
        return node;
    }

    private ObjectNode bytes(String hex) {
        if (!hex.matches(CryptoConstants.HEX_CASE_INSENSITIVE_PATTERN) || hex.length() % 2 != 0) {
            throw new RegistryConflictException("Expected hex bytes for script data: " + hex);
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put(BYTES_FIELD, hex.toLowerCase());
        return node;
    }

    private ObjectNode integer(long value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(INTEGER_FIELD, value);
        return node;
    }

    private ArrayNode fields(JsonNode node, int constructor) {
        if (constructorIndex(node) != constructor || !node.path(FIELDS_FIELD).isArray()) {
            throw new IllegalArgumentException("Unexpected constructor shape");
        }
        return (ArrayNode) node.path(FIELDS_FIELD);
    }

    private int constructorIndex(JsonNode node) {
        return node.path(CONSTRUCTOR_FIELD).asInt(-1);
    }

    private String bytesValue(JsonNode node) {
        String value = node.path(BYTES_FIELD).asText("");
        if (!value.matches(CryptoConstants.HEX_CASE_INSENSITIVE_PATTERN) || value.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid bytes value");
        }
        return value.toLowerCase();
    }

    private int intValue(JsonNode node) {
        return Math.toIntExact(longValue(node));
    }

    private long longValue(JsonNode node) {
        if (!node.path(INTEGER_FIELD).canConvertToLong()) {
            throw new IllegalArgumentException("Invalid integer value");
        }
        return node.path(INTEGER_FIELD).longValue();
    }

    private List<String> decodeBytesList(JsonNode node) {
        if (!node.path(LIST_FIELD).isArray()) {
            throw new IllegalArgumentException("Invalid list value");
        }
        List<String> values = new ArrayList<>();
        node.path(LIST_FIELD).forEach(value -> values.add(bytesValue(value)));
        return List.copyOf(values);
    }

    private String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode Aiken script data", ex);
        }
    }
}
