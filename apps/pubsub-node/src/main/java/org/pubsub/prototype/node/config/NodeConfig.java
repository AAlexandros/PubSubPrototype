package org.pubsub.prototype.node.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.pubsub.prototype.transport.PeerEndpoint;
import org.pubsub.prototype.transport.TransportConfig;
import org.pubsub.prototype.util.Validation;
import org.pubsub.prototype.util.YamlFiles;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;

import java.nio.file.Path;
import java.util.List;

import static org.pubsub.prototype.node.config.NodeConfigConstants.*;
import static org.pubsub.prototype.util.Validators.require;
import static org.pubsub.prototype.util.Validators.requireNonNull;

public record NodeConfig(NodeSection node, List<PeerSection> peers, TransportSection transport,
                         RegistrySection registry, ControlSection control,
                         @JsonProperty(PEER_SAMPLING_SECTION) SamplingSection sampling,
                         NavigationSection navigation, DisseminationSection dissemination,
                         PersistenceSection persistence) {
    public static NodeConfig load(Path path) {
        NodeConfig config = YamlFiles.read(path, NodeConfig.class);
        validate(config);
        return config;
    }

    public TransportConfig toTransportConfig() {
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

    public Path identityPath() {
        return Path.of(node.identityPath);
    }

    public String controlHost() {
        return control.host;
    }

    public int controlPort() {
        return control.port;
    }

    public Path subscriptionsPath() {
        return Path.of(navigation.subscriptionsPath);
    }

    private static void validate(NodeConfig config) {
        require(config != null && config.node != null && config.peers != null && config.transport != null
                        && config.control != null,
                "Config must define node, peers, transport, and control sections");
        Validation.start()
                .nonBlank(config.node.name, NODE_NAME)
                .nonBlank(config.node.listenHost, NODE_LISTEN_HOST)
                .nonBlank(config.node.identityPath, NODE_IDENTITY_PATH)
                .port(config.node.listenPort, NODE_LISTEN_PORT)
                .throwIfInvalid();
        for (int index = 0; index < config.peers.size(); index++) {
            String peerPath = PEERS + "[" + index + "]";
            PeerSection peer = requireNonNull(config.peers.get(index), peerPath);
            Validation.start()
                    .nonBlank(peer.host, peerPath + "." + PEER_HOST)
                    .port(peer.port, peerPath + "." + PEER_PORT)
                    .throwIfInvalid();
        }
        require(config.transport.pingIntervalMs != null && config.transport.pingIntervalMs > 0
                        && config.transport.pingTimeoutMs != null && config.transport.pingTimeoutMs > 0
                        && config.transport.reconnectInitialMs != null && config.transport.reconnectInitialMs > 0
                        && config.transport.reconnectMaxMs != null
                        && config.transport.reconnectMaxMs >= config.transport.reconnectInitialMs,
                "Invalid transport configuration");
        Validation.start()
                .nonBlank(config.control.host, CONTROL_HOST)
                .port(config.control.port, CONTROL_PORT)
                .throwIfInvalid();

        require(config.registry != null && config.sampling != null && config.navigation != null
                        && config.dissemination != null && config.persistence != null,
                "Pub-sub config must define registry, peerSampling, navigation, dissemination, and persistence sections");
        Validation.start()
                .nonBlank(config.registry.runtimeDir, REGISTRY_RUNTIME_DIR)
                .nonBlank(config.registry.signer, REGISTRY_SIGNER)
                .required(config.registry.cliBackend, REGISTRY_CLI_BACKEND)
                .positive(config.registry.pollIntervalMs, REGISTRY_POLL_INTERVAL_MS)
                .throwIfInvalid();

        int maximumSwapLength = config.sampling.viewSize == null ? MAX_OVERLAY_SIZE : config.sampling.viewSize;
        Validation.start()
                .nonBlank(config.sampling.advertisedHost, SAMPLING_ADVERTISED_HOST)
                .range(config.sampling.viewSize, MINIMUM_POSITIVE_VALUE, MAX_OVERLAY_SIZE, SAMPLING_VIEW_SIZE)
                .range(config.sampling.swapLength, MINIMUM_POSITIVE_VALUE,
                        maximumSwapLength, SAMPLING_SWAP_LENGTH)
                .positive(config.sampling.cycleIntervalMs, SAMPLING_CYCLE_INTERVAL_MS)
                .range(config.sampling.ageThreshold, 0, MAX_AGE_THRESHOLD, SAMPLING_AGE_THRESHOLD)
                .positive(config.sampling.proofFanout, SAMPLING_PROOF_FANOUT)
                .required(config.sampling.randomSeed, SAMPLING_RANDOM_SEED)
                .throwIfInvalid();
        requireValidHistoryAge(config.sampling);

        Validation.start()
                .range(config.navigation.capacity, MINIMUM_POSITIVE_VALUE, MAX_OVERLAY_SIZE, NAVIGATION_CAPACITY)
                .range(config.navigation.routingBase, MIN_ROUTING_BASE, MAX_ROUTING_BASE, NAVIGATION_ROUTING_BASE)
                .positive(config.navigation.cycleIntervalMs, NAVIGATION_CYCLE_INTERVAL_MS)
                .positive(config.navigation.staleAfterMs, NAVIGATION_STALE_AFTER_MS)
                .nonBlank(config.navigation.subscriptionsPath, NAVIGATION_SUBSCRIPTIONS_PATH)
                .throwIfInvalid();

        Validation.start()
                .positive(config.dissemination.randomLinkCount, DISSEMINATION_RANDOM_LINK_COUNT)
                .positive(config.dissemination.cycleIntervalMs, DISSEMINATION_CYCLE_INTERVAL_MS)
                .positive(config.dissemination.staleAfterMs, DISSEMINATION_STALE_AFTER_MS)
                .required(config.dissemination.randomSeed, DISSEMINATION_RANDOM_SEED)
                .throwIfInvalid();

        Validation.start()
                .nonBlank(config.persistence.membershipPath, PERSISTENCE_MEMBERSHIP_PATH)
                .nonBlank(config.persistence.deliveryStatePath, PERSISTENCE_DELIVERY_STATE_PATH)
                .positive(config.persistence.connectionTimeoutMs, PERSISTENCE_CONNECTION_TIMEOUT_MS)
                .positive(config.persistence.requestTimeoutMs, PERSISTENCE_REQUEST_TIMEOUT_MS)
                .nonNegative(config.persistence.retries, PERSISTENCE_RETRIES)
                .positive(config.persistence.recoveryConcurrency, PERSISTENCE_RECOVERY_CONCURRENCY)
                .throwIfInvalid();
    }

    private static void requireValidHistoryAge(SamplingSection sampling) {
        try {
            Math.multiplyExact(sampling.cycleIntervalMs,
                    (long) sampling.viewSize + sampling.ageThreshold);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "peerSampling history age exceeds the supported range", exception);
        }
    }

    public static final class SamplingSection {
        public String advertisedHost;
        public Integer viewSize;
        public Integer swapLength;
        public Long cycleIntervalMs;
        public Integer ageThreshold;
        public Integer proofFanout;
        public Long randomSeed;
    }

    public static final class NavigationSection {
        public Integer capacity;
        public Integer routingBase;
        public Long cycleIntervalMs;
        public Long staleAfterMs;
        public String subscriptionsPath;
    }

    public static final class DisseminationSection {
        public Integer randomLinkCount;
        public Long cycleIntervalMs;
        public Long staleAfterMs;
        public Long randomSeed;
    }

    public static final class PersistenceSection {
        public String membershipPath;
        public String deliveryStatePath;
        public Long connectionTimeoutMs;
        public Long requestTimeoutMs;
        public Integer retries;
        public Integer recoveryConcurrency;
    }

    public static final class NodeSection {
        public String name;
        public String listenHost;
        public Integer listenPort;
        public String identityPath;
    }

    public static final class PeerSection {
        public String host;
        public Integer port;
    }

    public static final class TransportSection {
        public Long pingIntervalMs;
        public Long pingTimeoutMs;
        public Long reconnectInitialMs;
        public Long reconnectMaxMs;
    }

    public static final class RegistrySection {
        public String runtimeDir;
        public String signer;
        public CardanoCliBackend cliBackend;
        public Long pollIntervalMs;
    }

    public static final class ControlSection {
        public String host;
        public Integer port;
    }
}
