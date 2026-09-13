package org.pubsub.prototype.sampling;

import java.util.List;
import java.util.Optional;

public interface PeerSamplingService {
    List<NodeEndpoint> view();
    Optional<NodeEndpoint> randomPeer();
}
