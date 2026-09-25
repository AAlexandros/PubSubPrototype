package org.pubsub.prototype.transport;

/**
 * Stores peer's host and port.
 * Its does not validate, but just stores the values.
 */
public record PeerEndpoint(String host, int port) {
    public String key() {
        return host + ":" + port;
    }
}
