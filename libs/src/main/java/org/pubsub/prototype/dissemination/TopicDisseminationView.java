package org.pubsub.prototype.dissemination;

import java.util.List;

/** Immutable inspection snapshot of one topic's Hybrid Dissemination view. */
public record TopicDisseminationView(
        String topicId,
        DisseminationPeerDescriptor predecessor,
        DisseminationPeerDescriptor successor,
        List<DisseminationPeerDescriptor> randomPeers) {
    public TopicDisseminationView {
        randomPeers = List.copyOf(randomPeers);
    }
}
