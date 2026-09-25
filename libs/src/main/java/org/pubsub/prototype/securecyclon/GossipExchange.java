package org.pubsub.prototype.securecyclon;

import java.util.List;

/** A SecureCyclon gossip exchange containing owned descriptors, samples, and violation proofs. */
public record GossipExchange(List<NodeDescriptor> descriptors, List<NodeDescriptor> samples,
                             List<ViolationProof> proofs) {
    public GossipExchange(List<NodeDescriptor> descriptors, List<NodeDescriptor> samples) {
        this(descriptors, samples, List.of());
    }
    public GossipExchange {
        proofs = List.copyOf(proofs);
        descriptors = List.copyOf(descriptors);
        samples = List.copyOf(samples);
    }
}
