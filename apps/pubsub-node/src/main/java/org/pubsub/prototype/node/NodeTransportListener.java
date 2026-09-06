package org.pubsub.prototype.node;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.PeerDescriptor;
import org.pubsub.prototype.transport.TransportListener;

/** Dispatches transport events to the peer-sampling, navigation, and event sub-listeners by message type. */
final class NodeTransportListener implements TransportListener {
    private final PeerSamplingRuntime sampling;
    private final NavigationRuntime navigation;
    private final NodeEventService events;

    NodeTransportListener(PeerSamplingRuntime sampling, NavigationRuntime navigation, NodeEventService events) {
        this.sampling = sampling;
        this.navigation = navigation;
        this.events = events;
    }

    @Override
    public void seedResolved(PeerDescriptor peer) {
        if (sampling != null) sampling.seedResolved(peer);
    }

    @Override
    public void samplingReceived(NodeId peer, ProtocolMessage message) {
        switch (message.type()) {
            case SECURECYCLON_REQUEST, SECURECYCLON_RESPONSE, SECURECYCLON_REPORT -> {
                if (sampling != null) sampling.samplingReceived(peer, message);
            }
            case NAVIGATION_REQUEST, NAVIGATION_RESPONSE -> {
                if (navigation != null) navigation.messageReceived(peer, message);
            }
            default -> {
            }
        }
    }

    @Override
    public void eventReceived(NodeId peerNodeId, EventEnvelope event) {
        events.eventReceived(peerNodeId, event);
    }
}
