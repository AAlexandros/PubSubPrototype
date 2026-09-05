package org.pubsub.prototype.sampling;

/** Transport-independent advertised listening endpoint. */
public record PeerDescriptor(String nodeId, String host, int port) {
    public PeerDescriptor {
        if (nodeId == null || !nodeId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid peer nodeId");
        if (host == null || host.isBlank() || host.length() > 253 || !host.matches("[a-zA-Z0-9.:-]+")
                || host.equals("0.0.0.0") || host.equals("::") || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid advertised endpoint");
    }
}
