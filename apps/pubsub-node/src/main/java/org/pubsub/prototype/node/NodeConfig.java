package org.pubsub.prototype.node;

import org.pubsub.prototype.transport.PeerEndpoint;
import org.pubsub.prototype.transport.TransportConfig;

import java.nio.file.Path;
import java.util.List;

record NodeConfig(NodeSection node, List<PeerSection> peers, TransportSection transport, RegistrySection registry, ControlSection control) {
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

    String controlHost() {
        return control == null || control.host == null ? "127.0.0.1" : control.host;
    }

    int controlPort() {
        return control == null || control.port == 0 ? 8000 : control.port;
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

    static final class ControlSection {
        public String host;
        public int port;
    }
}
