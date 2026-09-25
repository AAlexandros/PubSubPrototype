package org.pubsub.prototype.transport;

import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.event.EventEnvelope;

/**
 * Delivers authenticated peer events and messages to higher-level node protocols.
 * Overlay messages go to their respective runtimes; events go to validation and
 * dissemination services. Default methods let implementations handle only what
 * they need.
 */
public interface TransportListener {
    /** Adds a resolved seed to peer sampling. */
    default void seedResolved(NodeEndpoint peer) {
    }

    /**
     * Routes SecureCyclon, Navigation, and Dissemination exchanges to their runtimes.
     * The name is historical: this callback is not limited to peer sampling.
     */
    default void samplingReceived(NodeId peer, ProtocolMessage message) {
    }

    /** Announces an active authenticated peer. */
    default void peerConnected(NodeId nodeId) {
    }

    /** Lets node protocols remove or replace a disconnected peer. */
    default void peerDisconnected(NodeId nodeId) {
    }

    /** Exposes peer RTT for monitoring or routing decisions. */
    default void pongReceived(NodeId nodeId, long rttMs) {
    }

    /** Sends an incoming event to validation, deduplication, and dissemination. */
    default void eventReceived(NodeId peerNodeId, EventEnvelope event) {
    }
}
