package org.pubsub.prototype.sampling;

import static org.pubsub.prototype.constants.NetworkConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;
import static org.pubsub.prototype.constants.NetworkConstants.INVALID_PEER_NODE_ID_MESSAGE;
import static org.pubsub.prototype.util.validation.Validators.isAdvertisedEndpoint;
import static org.pubsub.prototype.util.validation.Validators.isSha256Hex;
import static org.pubsub.prototype.util.validation.Validation.start;

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
