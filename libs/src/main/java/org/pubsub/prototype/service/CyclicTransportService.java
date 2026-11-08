package org.pubsub.prototype.service;

import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.transport.TransportSender;

/**
 * Contract for a cyclic service that exchanges peer messages through a transport.
 * The application attaches a {@link TransportSender} before starting the service.
 */
public interface CyclicTransportService extends CyclicService {
    /** Binds the outbound transport operations used to send protocol messages. */
    void attach(TransportSender sender);

    /** Handles an inbound protocol message from a peer. */
    void messageReceived(NodeId peer, ProtocolMessage message);
}
