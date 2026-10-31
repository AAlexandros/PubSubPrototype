package org.pubsub.prototype.registry;

/** An on-chain registry query returned malformed or contradictory data. */
public class RegistryDataException extends IllegalArgumentException {
    public RegistryDataException(String message) { super(message); }
    public RegistryDataException(String message, Throwable cause) { super(message, cause); }
}
