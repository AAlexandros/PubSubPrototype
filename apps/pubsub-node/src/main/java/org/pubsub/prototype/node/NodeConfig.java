package org.pubsub.prototype.node;

import org.pubsub.prototype.transport.PeerEndpoint;
import org.pubsub.prototype.transport.TransportConfig;

import java.nio.file.Path;
import java.util.List;

record NodeConfig(NodeSection node, List<PeerSection> peers, TransportSection transport, RegistrySection registry) {
    TransportConfig toTransportConfig() {
        return new TransportConfig(
                node.name,
                node.listenHost,
                node.listenPort,
                peers.stream().map(peer -> new PeerEndpoint(peer.host, peer.port)).toList(),
                transport.pingIntervalMs,
                transport.pingTimeoutMs,
                transport.reconnectInitialMs,
                transport.reconnectMaxMs
        );
    }

    Path identityPath() {
        return Path.of(node.identityPath);
    }

    boolean registryEnabled() {
        return registry != null && registry.enabled;
    }

    static final class NodeSection {
        public String name;
        public String listenHost;
        public int listenPort;
        public String identityPath;
    }

    static final class PeerSection {
        public String host;
        public int port;
    }

    static final class TransportSection {
        public long pingIntervalMs;
        public long pingTimeoutMs;
        public long reconnectInitialMs;
        public long reconnectMaxMs;
    }

    static final class RegistrySection {
        public boolean enabled;
        public String runtimeDir;
        public String signer;
        public long pollIntervalMs;
    }
}
