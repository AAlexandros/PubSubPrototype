package org.pubsub.prototype.registry.cardano;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class JsonCodec {
    private final ObjectMapper mapper;

    JsonCodec() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    ObjectMapper mapper() {
        return mapper;
    }

    String pretty(Object value, String description) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode " + description, ex);
        }
    }

    String compact(Object value, String description) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode " + description, ex);
        }
    }

    <T> T read(String json, Class<T> type, String description) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to decode " + description, ex);
        }
    }

    JsonNode readTree(String json, String description) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to decode " + description, ex);
        }
    }
}
