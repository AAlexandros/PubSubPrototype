package org.pubsub.prototype.registry;

/** A registry-dependent operation cannot proceed without a recent chain observation. */
public final class RegistryUnavailableException extends IllegalStateException {
    public RegistryUnavailableException(String message) {
        super(message);
    }
}
