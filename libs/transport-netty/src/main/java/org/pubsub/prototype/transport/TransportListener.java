package org.pubsub.prototype.transport;

import org.pubsub.prototype.protocol.NodeId;

public interface TransportListener {
    default void peerConnected(NodeId nodeId) {
    }

    default void peerDisconnected(NodeId nodeId) {
    }

    default void pongReceived(NodeId nodeId, long rttMs) {
    }
}
