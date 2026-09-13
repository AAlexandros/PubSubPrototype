package org.pubsub.prototype.sampling;

/** Transport-independent network endpoint advertised by a node. */
public record NodeEndpoint(String nodeId, String host, int port) {
    public NodeEndpoint {
        if (nodeId == null || !nodeId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid peer nodeId");
        if (host == null || host.isBlank() || host.length() > 253 || !host.matches("[a-zA-Z0-9.:-]+")
                || host.equals("0.0.0.0") || host.equals("::") || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid advertised endpoint");
    }
}
