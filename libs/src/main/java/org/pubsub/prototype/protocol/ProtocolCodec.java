package org.pubsub.prototype.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Converts {@link ProtocolMessage} instances between JSON bytes and Java objects.
 * Message validity belongs to {@code ProtocolMessage}; this codec centralizes
 * Jackson configuration and reports malformed incoming payloads as {@link ProtocolException}.
 */
public final class ProtocolCodec {
    private final ObjectMapper mapper;

    public ProtocolCodec() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public byte[] encode(ProtocolMessage message) {
        try {
            return mapper.writeValueAsBytes(message);
        } catch (JsonProcessingException ex) {
            throw new ProtocolException("Unable to encode protocol message", ex);
        }
    }

    public ProtocolMessage decode(byte[] payload) {
        try {
            return mapper.readValue(payload, ProtocolMessage.class);
        } catch (IOException | IllegalArgumentException ex) {
            throw new ProtocolException("Malformed protocol message: " + new String(payload, StandardCharsets.UTF_8), ex);
        }
    }
}
