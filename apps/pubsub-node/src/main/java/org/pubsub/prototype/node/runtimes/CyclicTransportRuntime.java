package org.pubsub.prototype.node.runtimes;

import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.transport.PubSubTransport;

/**
 * A node runtime that periodically advances protocol state and handles one
 * family of inbound transport messages.
 *
 * <p>Persistence and registry synchronization do not implement this contract:
 * they do not participate in the Pub/Sub transport message cycle.</p>
 */
public interface CyclicTransportRuntime extends AutoCloseable {
    /** Binds the transport used to send protocol messages. */
    void attach(PubSubTransport transport);

    /** Starts periodic protocol processing. */
    void start();

    /** Executes one periodic protocol cycle. */
    void runCycle();

    /** Handles an inbound protocol message from a peer. */
    void messageReceived(NodeId peer, ProtocolMessage message);

    /** Stops processing and releases runtime resources. */
    @Override
    void close();
}
