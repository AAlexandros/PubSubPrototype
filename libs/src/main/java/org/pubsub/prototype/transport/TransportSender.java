package org.pubsub.prototype.transport;

import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.ProtocolMessage;
import org.pubsub.prototype.sampling.NodeEndpoint;

/**
 * Lets node services send protocol messages to other nodes.
 * A call does not confirm that another node received or accepted the message.
 */
public interface TransportSender {
    /** Sends a message to the specified peer. */
    void sendMessage(NodeEndpoint peer, ProtocolMessage message);

    /** Sends a reply to the specified peer. */
    void replyMessage(NodeId peer, ProtocolMessage message);

    /** Sends a message to all connected peers. */
    void broadcastMessage(ProtocolMessage message);
}
