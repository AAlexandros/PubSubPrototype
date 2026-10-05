package org.pubsub.prototype.transport;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.NodeEndpoint;

/** Outbound operations used by node runtimes without depending on the Netty transport implementation. */
public interface TransportSender {
    void sendSampling(NodeEndpoint peer, ProtocolMessage message);

    void replySampling(NodeId peer, ProtocolMessage message);

    void sendEvent(NodeEndpoint peer, EventEnvelope event);

    void broadcastEvent(EventEnvelope event);

    void forwardEvent(EventEnvelope event, NodeId exceptPeer);
}
