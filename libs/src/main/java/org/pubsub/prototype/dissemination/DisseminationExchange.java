package org.pubsub.prototype.dissemination;

import java.util.List;

import static org.pubsub.prototype.util.DisseminationConstants.CANDIDATES_FIELD;
import static org.pubsub.prototype.util.DisseminationConstants.INVALID_TOPIC_ID_MESSAGE;
import static org.pubsub.prototype.util.DisseminationConstants.SENDER_DESCRIPTOR_FIELD;
import static org.pubsub.prototype.util.Validators.isSha256Hex;
import static org.pubsub.prototype.util.Validators.require;
import static org.pubsub.prototype.util.Validators.requireNonNull;

/** Wire payload for a single-topic dissemination gossip exchange. */
public record DisseminationExchange(
        String topicId,
        DisseminationPeerDescriptor senderDescriptor,
        List<DisseminationPeerDescriptor> candidates,
        int protocolVersion) {
    public DisseminationExchange {
        require(isSha256Hex(topicId), INVALID_TOPIC_ID_MESSAGE);
        requireNonNull(senderDescriptor, SENDER_DESCRIPTOR_FIELD);
        candidates = List.copyOf(requireNonNull(candidates, CANDIDATES_FIELD));
        require(senderDescriptor.subscribedTopicIds().contains(topicId)
                        && candidates.stream().allMatch(peer -> peer.subscribedTopicIds().contains(topicId)),
                "Dissemination exchange contains an unrelated-topic peer");
    }
}
