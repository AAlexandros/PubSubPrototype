package org.pubsub.prototype.cardano.cli;

/** Indicates that the selected backend cannot execute live Cardano commands. */
public final class CardanoCliUnavailableException extends RuntimeException {
    /**
     * Creates an exception describing the unavailable operation.
     *
     * @param message failure description
     */
    public CardanoCliUnavailableException(String message) {
        super(message);
    }
}
