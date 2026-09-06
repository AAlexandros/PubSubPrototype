package org.pubsub.prototype.dissemination;

import java.util.List;
import java.util.Objects;

/** Wire payload for a single-topic dissemination gossip exchange. */
public record DisseminationExchange(
        String topicId,
        DisseminationPeerDescriptor senderDescriptor,
        List<DisseminationPeerDescriptor> candidates,
        int protocolVersion) {
    public DisseminationExchange {
        if (topicId == null || !topicId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid topicId");
        }
        Objects.requireNonNull(senderDescriptor, "senderDescriptor");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (!senderDescriptor.subscribedTopicIds().contains(topicId)
                || candidates.stream().anyMatch(peer -> !peer.subscribedTopicIds().contains(topicId))) {
            throw new IllegalArgumentException("Dissemination exchange contains an unrelated-topic peer");
        }
    }
}
