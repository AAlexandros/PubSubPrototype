package org.pubsub.prototype.node.adapter.transport;

import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.event.EventValidator;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.NodeEndpoint;
import org.pubsub.prototype.node.service.DisseminationService;
import org.pubsub.prototype.node.service.NavigationService;
import org.pubsub.prototype.node.service.PeerSamplingProtocolService;
import org.pubsub.prototype.node.service.EventProcessingService;
import org.pubsub.prototype.transport.TransportListener;

import java.util.Objects;

/** Dispatches transport events to the peer-sampling, navigation, and event sub-listeners by message type. */
public final class NodeTransportListener implements TransportListener {
    private final PeerSamplingProtocolService peerSamplingService;
    private final NavigationService navigationService;
    private final EventProcessingService eventService;
    private final DisseminationService disseminationService;
    private final EventValidator eventValidator;

    public NodeTransportListener(PeerSamplingProtocolService sampling, NavigationService navigation,
                                 DisseminationService dissemination, EventProcessingService events,
                                 EventValidator eventValidator) {
        this.peerSamplingService = Objects.requireNonNull(sampling);
        this.navigationService = Objects.requireNonNull(navigation);
        this.eventService = Objects.requireNonNull(events);
        this.eventValidator = Objects.requireNonNull(eventValidator);
        this.disseminationService = Objects.requireNonNull(dissemination);
    }

    @Override
    public void seedResolved(NodeEndpoint peer) {
        peerSamplingService.seedResolved(peer);
    }

    @Override
    public void samplingReceived(NodeId peer, ProtocolMessage message) {
        switch (message.type()) {
            case SECURECYCLON_REQUEST, SECURECYCLON_RESPONSE, SECURECYCLON_PROOF -> {
                peerSamplingService.messageReceived(peer, message);
            }
            case NAVIGATION_REQUEST, NAVIGATION_RESPONSE -> {
                navigationService.messageReceived(peer, message);
            }
            case DISSEMINATION_REQUEST, DISSEMINATION_RESPONSE -> {
                disseminationService.messageReceived(peer, message);
            }
            default -> {
            }
        }
    }

    @Override
    public void eventReceived(NodeId peerNodeId, EventEnvelope event) {
        eventService.handleIncomingEvent(peerNodeId, event, eventValidator.validate(event));
    }

    @Override
    public void peerDisconnected(NodeId nodeId) {
        peerSamplingService.peerDisconnected(nodeId);
        disseminationService.peerDisconnected(nodeId);
    }

    @Override
    public void peerConnected(NodeId nodeId) {
        peerSamplingService.peerConnected(nodeId);
    }
}
