package org.pubsub.prototype.protocol;

import java.security.KeyPair;

public record NodeIdentity(KeyPair keyPair, NodeId nodeId) {
}
