package org.pubsub.prototype.securecyclon;

/** Two conflicting node descriptors proving an ownership or frequency violation. */
public record ViolationProof(NodeDescriptor first, NodeDescriptor second) {}
