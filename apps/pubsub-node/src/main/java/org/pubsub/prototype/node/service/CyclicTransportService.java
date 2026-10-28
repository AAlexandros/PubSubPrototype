package org.pubsub.prototype.node.service;

import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.transport.TransportSender;

/**
 * Contract for a node overlay service that advances protocol state on a timer
 * and handles one family of inbound peer messages. The application attaches a
 * {@link TransportSender} before starting the service.
 *
 * <p>Persistence and registry synchronization do not implement this contract:
 * they do not participate in the Pub/Sub transport message cycle.</p>
 */
public interface CyclicTransportService extends AutoCloseable {
    /** Binds the outbound transport operations used to send protocol messages. */
    void attach(TransportSender sender);

    /** Starts periodic protocol processing. */
    void start();

    /** Executes one periodic protocol cycle. */
    void runCycle();

    /** Handles an inbound protocol message from a peer. */
    void messageReceived(NodeId peer, ProtocolMessage message);

    /** Stops processing and releases the service's resources. */
    @Override
    void close();
}
