package org.pubsub.prototype.dissemination;

import org.pubsub.prototype.navigation.NavigationPeerDescriptor;

import java.util.List;

import static org.pubsub.prototype.util.DisseminationConstants.INVALID_ADVERTISED_ENDPOINT_MESSAGE;
import static org.pubsub.prototype.util.DisseminationConstants.INVALID_PEER_NODE_ID_MESSAGE;
import static org.pubsub.prototype.util.DisseminationConstants.SUBSCRIBED_TOPIC_IDS_FIELD;
import static org.pubsub.prototype.util.SharedFieldNames.FRESHNESS;
import static org.pubsub.prototype.util.Validators.isAdvertisedEndpoint;
import static org.pubsub.prototype.util.Validators.isSha256Hex;
import static org.pubsub.prototype.util.Validators.requireNonNull;
import static org.pubsub.prototype.util.Validation.start;

/** A same-topic peer retained independently from Navigation's own view. */
public record DisseminationPeerDescriptor(
        String nodeId, String host, int port, List<String> subscribedTopicIds, long freshness) {
    public DisseminationPeerDescriptor {
        start()
                .require(isSha256Hex(nodeId), INVALID_PEER_NODE_ID_MESSAGE)
                .require(isAdvertisedEndpoint(host, port), INVALID_ADVERTISED_ENDPOINT_MESSAGE)
                .nonNegative(freshness, FRESHNESS)
                .throwIfInvalid();
        subscribedTopicIds = List.copyOf(requireNonNull(subscribedTopicIds, SUBSCRIBED_TOPIC_IDS_FIELD));
    }

    public static DisseminationPeerDescriptor fromNavigation(NavigationPeerDescriptor peer) {
        return new DisseminationPeerDescriptor(
                peer.nodeId(), peer.host(), peer.port(), peer.subscribedTopicIds(), peer.freshness());
    }
}
