package org.pubsub.prototype.transport;

import java.util.List;

/**
 * Netty transport configuration.
 */
public record TransportConfig(String nodeName, String listenHost, int listenPort, List<PeerEndpoint> peers,
                              long pingIntervalMs, long pingTimeoutMs, long reconnectInitialMs, long reconnectMaxMs) {
}
