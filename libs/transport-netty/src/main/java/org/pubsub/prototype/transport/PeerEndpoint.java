package org.pubsub.prototype.transport;

public record PeerEndpoint(String host, int port) {
    public String key() {
        return host + ":" + port;
    }
}
