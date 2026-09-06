package org.pubsub.prototype.navigation;

import java.util.List;
import java.util.Objects;

/** Navigation-layer peer descriptor. Distinct from the SecureCyclon view; never mutates it. */
public record NavigationPeerDescriptor(String nodeId, String host, int port, List<String> subscribedTopicIds, long freshness) {
    public NavigationPeerDescriptor {
        if (nodeId == null || !nodeId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid peer nodeId");
        if (host == null || host.isBlank() || host.length() > 253 || !host.matches("[a-zA-Z0-9.:-]+")
                || host.equals("0.0.0.0") || host.equals("::") || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid advertised endpoint");
        subscribedTopicIds = List.copyOf(Objects.requireNonNull(subscribedTopicIds, "subscribedTopicIds"));
        if (freshness < 0)
            throw new IllegalArgumentException("freshness must be non-negative");
    }
}
