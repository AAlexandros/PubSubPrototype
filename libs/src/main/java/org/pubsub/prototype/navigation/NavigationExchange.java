package org.pubsub.prototype.navigation;

import java.util.List;
import java.util.Objects;

import static org.pubsub.prototype.util.NavigationConstants.CANDIDATES_ARGUMENT;
import static org.pubsub.prototype.util.NavigationConstants.SENDER_DESCRIPTOR_ARGUMENT;
import static org.pubsub.prototype.util.NavigationConstants.SENDER_SUBSCRIPTIONS_ARGUMENT;

/** Wire payload for NAVIGATION_REQUEST/NAVIGATION_RESPONSE: sender descriptor, subscriptions, candidates, version. */
public record NavigationExchange(NavigationPeerDescriptor senderDescriptor, List<String> senderSubscriptions,
                                  List<NavigationPeerDescriptor> candidates, int protocolVersion) {
    public NavigationExchange {
        Objects.requireNonNull(senderDescriptor, SENDER_DESCRIPTOR_ARGUMENT);
        senderSubscriptions = List.copyOf(Objects.requireNonNull(senderSubscriptions, SENDER_SUBSCRIPTIONS_ARGUMENT));
        candidates = List.copyOf(Objects.requireNonNull(candidates, CANDIDATES_ARGUMENT));
    }
}
