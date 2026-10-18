package org.pubsub.prototype.protocol;

/** Public key, fresh challenge, and optional signature used during peer authentication. */
public record HandshakeProof(String publicKey, String challenge, String signature) { }
