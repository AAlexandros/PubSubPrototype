package org.pubsub.prototype.registry;

/** Thrown when a registry operation conflicts with its current state or constraints. */
public class RegistryConflictException extends RuntimeException {
    public RegistryConflictException(String message) {
        super(message);
    }
}
