package org.pubsub.prototype.registry;

/** A queried UTxO does not belong to the trusted registry identity. */
public final class RegistryIdentityException extends RegistryDataException {
    public RegistryIdentityException(String message) { super(message); }
}
