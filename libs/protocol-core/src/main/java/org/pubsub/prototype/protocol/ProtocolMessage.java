package org.pubsub.prototype.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.sampling.PeerDescriptor;
import org.pubsub.prototype.securecyclon.Exchange;

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
        EventEnvelope event,
        PeerDescriptor descriptor,
        Exchange exchange
) {
    public ProtocolMessage(MessageType type, int version, String nodeId, String nodeName,
                           UUID requestId, Instant sentAt, EventEnvelope event) {
        this(type, version, nodeId, nodeName, requestId, sentAt, event, null, null);
    }

    public ProtocolMessage withDescriptor(PeerDescriptor peer) {
        return new ProtocolMessage(type, version, nodeId, nodeName, requestId, sentAt, event, peer, exchange);
    }

    public static ProtocolMessage gossip(MessageType type, UUID id, Exchange exchange) {
        return new ProtocolMessage(type, VERSION, null, null, id, null, null, null, exchange);
    }

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
