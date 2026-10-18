package org.pubsub.prototype.replication.adapter.http.exception;

/** Signals that a valid client request could not be persisted or replicated. */
public final class PersistenceUnavailableException extends RuntimeException {
    public PersistenceUnavailableException(Throwable cause) {
        super("event persistence unavailable", cause);
    }
}
