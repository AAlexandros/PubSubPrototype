package org.pubsub.prototype.navigation;

import java.util.List;

import static org.pubsub.prototype.util.NavigationConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;
import static org.pubsub.prototype.util.NavigationConstants.INVALID_PEER_NODE_ID_MESSAGE;
import static org.pubsub.prototype.util.NavigationConstants.SUBSCRIBED_TOPIC_IDS_ARGUMENT;
import static org.pubsub.prototype.util.SharedFieldNames.FRESHNESS;
import static org.pubsub.prototype.util.Validators.isAdvertisedEndpoint;
import static org.pubsub.prototype.util.Validators.isSha256Hex;
import static org.pubsub.prototype.util.Validators.requireNonNull;
import static org.pubsub.prototype.util.Validation.start;

/** Constant navigation-layer peer descriptor, distinct from the SecureCyclon one */
public record NavigationPeerDescriptor(String nodeId, String host, int port, List<String> subscribedTopicIds, long freshness) {
    public NavigationPeerDescriptor {
        start()
                .require(isSha256Hex(nodeId), INVALID_PEER_NODE_ID_MESSAGE)
                .require(isAdvertisedEndpoint(host, port), INVALID_ADVERTISED_ENDPOINT_MESSAGE)
                .nonNegative(freshness, FRESHNESS)
                .throwIfInvalid();
        subscribedTopicIds = List.copyOf(requireNonNull(subscribedTopicIds, SUBSCRIBED_TOPIC_IDS_ARGUMENT));
    }
}
