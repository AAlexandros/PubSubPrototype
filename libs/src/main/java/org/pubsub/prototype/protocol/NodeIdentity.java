package org.pubsub.prototype.protocol;

import java.security.KeyPair;

/**
 * Object that stors a valid keypair and a nodeId.
 * This object does not perform any validations, they should have been performed beforhand.
 */
public record NodeIdentity(KeyPair keyPair, NodeId nodeId) {
}
