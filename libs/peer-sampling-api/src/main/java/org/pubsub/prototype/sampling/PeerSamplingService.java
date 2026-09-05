package org.pubsub.prototype.sampling;

import java.util.List;
import java.util.Optional;

public interface PeerSamplingService {
    List<PeerDescriptor> view();
    Optional<PeerDescriptor> randomPeer();
}
