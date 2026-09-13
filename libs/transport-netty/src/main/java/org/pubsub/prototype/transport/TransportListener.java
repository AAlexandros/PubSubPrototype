package org.pubsub.prototype.transport;

import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.event.EventEnvelope;

public interface TransportListener {
    default void seedResolved(NodeEndpoint peer) {}
    default void samplingReceived(NodeId peer, ProtocolMessage message) {}

    default void peerConnected(NodeId nodeId) {
    }

    default void peerDisconnected(NodeId nodeId) {
    }

    default void pongReceived(NodeId nodeId, long rttMs) {
    }

    default void eventReceived(NodeId peerNodeId, EventEnvelope event) {
    }
}
