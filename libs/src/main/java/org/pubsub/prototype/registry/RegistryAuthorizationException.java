package org.pubsub.prototype.registry;

/** Thrown when the registry signer is not authorized to perform an operation. */
public class RegistryAuthorizationException extends RuntimeException {
    public RegistryAuthorizationException(String message) {
        super(message);
    }
}
