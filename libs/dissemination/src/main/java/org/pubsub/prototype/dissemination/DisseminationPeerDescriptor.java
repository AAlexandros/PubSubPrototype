package org.pubsub.prototype.dissemination;

import org.pubsub.prototype.navigation.NavigationPeerDescriptor;

import java.util.List;
import java.util.Objects;

/** A same-topic peer retained independently from Navigation's own view. */
public record DisseminationPeerDescriptor(
        String nodeId, String host, int port, List<String> subscribedTopicIds, long freshness) {
    public DisseminationPeerDescriptor {
        if (nodeId == null || !nodeId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid peer nodeId");
        }
        if (host == null || host.isBlank() || host.length() > 253 || !host.matches("[a-zA-Z0-9.:-]+")
                || host.equals("0.0.0.0") || host.equals("::") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid advertised endpoint");
        }
        subscribedTopicIds = List.copyOf(Objects.requireNonNull(subscribedTopicIds, "subscribedTopicIds"));
        if (freshness < 0) {
            throw new IllegalArgumentException("freshness must be non-negative");
        }
    }

    public static DisseminationPeerDescriptor fromNavigation(NavigationPeerDescriptor peer) {
        return new DisseminationPeerDescriptor(
                peer.nodeId(), peer.host(), peer.port(), peer.subscribedTopicIds(), peer.freshness());
    }
}
