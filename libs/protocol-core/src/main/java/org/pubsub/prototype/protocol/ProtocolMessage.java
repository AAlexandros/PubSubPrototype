package org.pubsub.prototype.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.pubsub.prototype.event.EventEnvelope;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtocolMessage(
        MessageType type,
        int version,
        String nodeId,
        String nodeName,
        UUID requestId,
        Instant sentAt,
        EventEnvelope event
) {
    public static final int VERSION = 1;

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
}
