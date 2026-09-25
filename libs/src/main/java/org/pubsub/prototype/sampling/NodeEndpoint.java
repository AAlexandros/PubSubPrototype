package org.pubsub.prototype.sampling;

import static org.pubsub.prototype.util.NetworkConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;
import static org.pubsub.prototype.util.NetworkConstants.INVALID_PEER_NODE_ID_MESSAGE;
import static org.pubsub.prototype.util.Validators.isAdvertisedEndpoint;
import static org.pubsub.prototype.util.Validators.isSha256Hex;
import static org.pubsub.prototype.util.Validation.start;

/**
 * Transport-independent network endpoint advertised by a node.
 */
public record NodeEndpoint(String nodeId, String host, int port) {
    public NodeEndpoint {
        start()
                .require(isSha256Hex(nodeId), INVALID_PEER_NODE_ID_MESSAGE)
                .require(isAdvertisedEndpoint(host, port), INVALID_ADVERTISED_ENDPOINT_MESSAGE)
                .throwIfInvalid();
    }
}
