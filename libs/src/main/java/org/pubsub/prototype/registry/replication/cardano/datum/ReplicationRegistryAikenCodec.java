package org.pubsub.prototype.registry.replication.cardano.datum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.pubsub.prototype.persistence.ReplicationServerState;
import org.pubsub.prototype.registry.replication.cardano.mutation.ReplicationServerMutation;
import org.pubsub.prototype.registry.replication.cardano.mutation.ReplicationServerOperation;
import org.pubsub.prototype.util.JsonSupport;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Converts replication-registry states and actions to and from Aiken Plutus-data JSON. */
public final class ReplicationRegistryAikenCodec {
    private static final HexFormat HEX = HexFormat.of();

    public String encodeDatum(ReplicationServerState state) {
        ObjectNode datum = constructor(0);
        ArrayNode fields = datum.withArray("fields");
        fields.add(bytes(state.serverId()));
        fields.add(bytes(state.operator()));
        fields.add(bytes(HEX.formatHex(state.host().getBytes(StandardCharsets.UTF_8))));
        fields.add(integer(state.port()));
        fields.add(integer(state.commitmentStartEpoch()));
        fields.add(integer(state.commitmentEndEpoch()));
        fields.add(constructor(state.active() ? 1 : 0));
        return pretty(datum);
    }

    public String encodeRedeemer(ReplicationServerMutation mutation) {
        if (mutation.operation() == ReplicationServerOperation.UNREGISTER_SERVER) return pretty(constructor(1));
        ObjectNode redeemer = constructor(0);
        ArrayNode fields = redeemer.withArray("fields");
        fields.add(bytes(HEX.formatHex(mutation.host().getBytes(StandardCharsets.UTF_8))));
        fields.add(integer(mutation.port()));
        fields.add(integer(mutation.commitmentStartEpoch()));
        fields.add(integer(mutation.commitmentEndEpoch()));
        return pretty(redeemer);
    }

    public ReplicationServerState decodeDatum(JsonNode datum) {
        ArrayNode fields = fields(datum, 0, 7);
        return new ReplicationServerState(
                bytesValue(fields.get(0)),
                bytesValue(fields.get(1)),
                new String(HEX.parseHex(bytesValue(fields.get(2))), StandardCharsets.UTF_8),
                Math.toIntExact(integerValue(fields.get(3))),
                integerValue(fields.get(4)),
                integerValue(fields.get(5)),
                constructorIndex(fields.get(6)) == 1
        );
    }

    private static ObjectNode constructor(int index) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.put("constructor", index);
        node.set("fields", JsonSupport.MAPPER.createArrayNode());
        return node;
    }

    private static ObjectNode bytes(String value) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.put("bytes", value.toLowerCase());
        return node;
    }

    private static ObjectNode integer(long value) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.put("int", value);
        return node;
    }

    private static ArrayNode fields(JsonNode node, int constructor, int count) {
        if (constructorIndex(node) != constructor || !node.path("fields").isArray()
                || node.path("fields").size() != count) {
            throw new IllegalArgumentException("Unexpected replication-registry datum shape");
        }
        return (ArrayNode) node.path("fields");
    }

    private static int constructorIndex(JsonNode node) {
        return node.path("constructor").asInt(-1);
    }

    private static String bytesValue(JsonNode node) {
        String value = node.path("bytes").asText("");
        if (!value.matches("(?i)([0-9a-f]{2})*")) throw new IllegalArgumentException("Invalid datum bytes");
        return value.toLowerCase();
    }

    private static long integerValue(JsonNode node) {
        if (!node.path("int").canConvertToLong()) throw new IllegalArgumentException("Invalid datum integer");
        return node.path("int").longValue();
    }

    private static String pretty(JsonNode node) {
        try {
            return JsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode replication-registry script data", ex);
        }
    }
}
