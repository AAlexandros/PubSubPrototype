package org.pubsub.prototype.navigation;

import java.util.List;
import java.util.Objects;

/** Wire payload for NAVIGATION_REQUEST/NAVIGATION_RESPONSE: sender descriptor, subscriptions, candidates, version. */
public record NavigationExchange(NavigationPeerDescriptor senderDescriptor, List<String> senderSubscriptions,
                                  List<NavigationPeerDescriptor> candidates, int protocolVersion) {
    public NavigationExchange {
        Objects.requireNonNull(senderDescriptor, "senderDescriptor");
        senderSubscriptions = List.copyOf(Objects.requireNonNull(senderSubscriptions, "senderSubscriptions"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
}
