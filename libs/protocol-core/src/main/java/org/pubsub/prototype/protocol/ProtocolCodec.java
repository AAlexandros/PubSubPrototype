package org.pubsub.prototype.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ProtocolCodec {
    private final ObjectMapper mapper;

    public ProtocolCodec() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public byte[] encode(ProtocolMessage message) {
        validate(message);
        try {
            return mapper.writeValueAsBytes(message);
        } catch (JsonProcessingException ex) {
            throw new ProtocolException("Unable to encode protocol message", ex);
        }
    }

    public ProtocolMessage decode(byte[] payload) {
        try {
            ProtocolMessage message = mapper.readValue(payload, ProtocolMessage.class);
            validate(message);
            return message;
        } catch (IOException | IllegalArgumentException ex) {
            throw new ProtocolException("Malformed protocol message: " + new String(payload, StandardCharsets.UTF_8), ex);
        }
    }

    public void validate(ProtocolMessage message) {
        if (message == null || message.type() == null) {
            throw new ProtocolException("Message type is required");
        }
        if (message.version() != ProtocolMessage.VERSION) {
            throw new ProtocolException("Unsupported protocol version: " + message.version());
        }
        switch (message.type()) {
            case HELLO -> {
                requireNodeId(message.nodeId());
                if (message.nodeName() == null || message.nodeName().isBlank()) {
                    throw new ProtocolException("HELLO nodeName is required");
                }
            }
            case HELLO_ACK -> requireNodeId(message.nodeId());
            case PING, PONG -> {
                if (message.requestId() == null || message.sentAt() == null) {
                    throw new ProtocolException(message.type() + " requestId and sentAt are required");
                }
            }
            case SECURECYCLON_REQUEST, SECURECYCLON_RESPONSE, SECURECYCLON_REPORT -> {
                if (message.requestId() == null || message.exchange() == null)
                    throw new ProtocolException("SecureCyclon requestId and exchange required");
            }
            case NAVIGATION_REQUEST, NAVIGATION_RESPONSE -> {
                if (message.requestId() == null || message.navigation() == null)
                    throw new ProtocolException("Navigation requestId and exchange required");
            }
            case DISSEMINATION_REQUEST, DISSEMINATION_RESPONSE -> {
                if (message.requestId() == null || message.dissemination() == null)
                    throw new ProtocolException("Dissemination requestId and exchange required");
                if (message.dissemination().protocolVersion() != org.pubsub.prototype.dissemination.DisseminationEngine.PROTOCOL_VERSION)
                    throw new ProtocolException("Unsupported dissemination exchange version");
            }
            case EVENT -> {
                if (message.event() == null) {
                    throw new ProtocolException("EVENT envelope is required");
                }
            }
        }
    }

    private static void requireNodeId(String value) {
        try {
            new NodeId(value);
        } catch (RuntimeException ex) {
            throw new ProtocolException("Invalid nodeId", ex);
        }
    }
}
