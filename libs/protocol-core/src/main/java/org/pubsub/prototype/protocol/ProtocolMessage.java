package org.pubsub.prototype.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.pubsub.prototype.dissemination.DisseminationEngine;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.navigation.NavigationExchange;
import org.pubsub.prototype.dissemination.DisseminationExchange;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.securecyclon.GossipExchange;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned wire envelope for all node-to-node protocol messages.
 *
 * <p>Only fields relevant to the message {@link #type()} are populated;
 * unused fields are {@code null}. Peer identity is established during the
 * handshake and is not repeated in gossip messages. Construction validates
 * the fields required by the message type and rejects invalid messages with
 * {@link IllegalArgumentException}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtocolMessage(
        MessageType type,
        int version,
        String nodeId,
        String nodeName,
        UUID requestId,
        Instant sentAt,
        EventEnvelope event,
        NodeEndpoint endpoint,
        GossipExchange exchange,
        NavigationExchange navigation,
        DisseminationExchange dissemination
) {
    public static final int VERSION = 1;

    public ProtocolMessage {
        Objects.requireNonNull(type, "type");
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }
        switch (type) {
            case HELLO -> {
                requireNodeId(nodeId);
                requireNonBlank(nodeName, "HELLO nodeName is required");
            }
            case HELLO_ACK -> requireNodeId(nodeId);
            case PING, PONG -> require(requestId != null && sentAt != null,
                    type + " requestId and sentAt are required");
            case SECURECYCLON_REQUEST, SECURECYCLON_RESPONSE, SECURECYCLON_PROOF ->
                    require(requestId != null && exchange != null,
                            "SecureCyclon requestId and exchange required");
            case NAVIGATION_REQUEST, NAVIGATION_RESPONSE ->
                    require(requestId != null && navigation != null,
                            "Navigation requestId and exchange required");
            case DISSEMINATION_REQUEST, DISSEMINATION_RESPONSE -> {
                require(requestId != null && dissemination != null,
                        "Dissemination requestId and exchange required");
                require(dissemination.protocolVersion() == DisseminationEngine.PROTOCOL_VERSION,
                        "Unsupported dissemination exchange version");
            }
            case EVENT -> require(event != null, "EVENT envelope is required");
        }
    }

    public ProtocolMessage(MessageType type, int version, String nodeId, String nodeName,
                           UUID requestId, Instant sentAt, EventEnvelope event) {
        this(type, version, nodeId, nodeName, requestId, sentAt, event, null, null, null, null);
    }

    public ProtocolMessage withEndpoint(NodeEndpoint endpoint) {
        return new ProtocolMessage(type, version, nodeId, nodeName, requestId, sentAt, event, endpoint, exchange, navigation, dissemination);
    }

    public static ProtocolMessage gossip(MessageType type, UUID id, GossipExchange exchange) {
        return new ProtocolMessage(type, VERSION, null, null, id, null, null, null, exchange, null, null);
    }

    public static ProtocolMessage navigationGossip(MessageType type, UUID id, NavigationExchange navigation) {
        return new ProtocolMessage(type, VERSION, null, null, id, null, null, null, null, navigation, null);
    }

    public static ProtocolMessage disseminationGossip(MessageType type, UUID id, DisseminationExchange dissemination) {
        return new ProtocolMessage(type, VERSION, null, null, id, null, null, null, null, null, dissemination);
    }

    public static ProtocolMessage hello(NodeId nodeId, String nodeName) {
        return new ProtocolMessage(MessageType.HELLO, VERSION, nodeId.value(), nodeName, null, null, null);
    }

    public static ProtocolMessage helloAck(NodeId nodeId) {
        return new ProtocolMessage(MessageType.HELLO_ACK, VERSION, nodeId.value(), null, null, null, null);
    }

    public static ProtocolMessage ping(UUID requestId, Instant sentAt) {
        return new ProtocolMessage(MessageType.PING, VERSION, null, null, requestId, sentAt, null);
    }

    public static ProtocolMessage pong(UUID requestId, Instant sentAt) {
        return new ProtocolMessage(MessageType.PONG, VERSION, null, null, requestId, sentAt, null);
    }

    public static ProtocolMessage event(EventEnvelope event) {
        return new ProtocolMessage(MessageType.EVENT, VERSION, null, null, null, null, event);
    }

    private static void requireNodeId(String value) {
        try {
            new NodeId(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid nodeId", ex);
        }
    }

    private static void requireNonBlank(String value, String message) {
        require(value != null && !value.isBlank(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
