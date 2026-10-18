package org.pubsub.prototype.navigation;

import java.util.List;

import static org.pubsub.prototype.constants.NavigationConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;
import static org.pubsub.prototype.constants.NavigationConstants.INVALID_PEER_NODE_ID_MESSAGE;
import static org.pubsub.prototype.constants.NavigationConstants.SUBSCRIBED_TOPIC_IDS_ARGUMENT;
import static org.pubsub.prototype.constants.SharedFieldNames.FRESHNESS;
import static org.pubsub.prototype.util.validation.Validators.isAdvertisedEndpoint;
import static org.pubsub.prototype.util.validation.Validators.isSha256Hex;
import static org.pubsub.prototype.util.validation.Validators.requireNonNull;
import static org.pubsub.prototype.util.validation.Validation.start;

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
