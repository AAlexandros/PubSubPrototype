package org.pubsub.prototype.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.node.config.NodeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsTypedConfiguration() throws IOException {
        NodeConfig config = load("""
                node:
                  name: node-1
                  listenHost: 0.0.0.0
                  listenPort: 7000
                  identityPath: /data/identity
                peers: []
                transport:
                  pingIntervalMs: 5000
                  pingTimeoutMs: 3000
                  reconnectInitialMs: 1000
                  reconnectMaxMs: 10000
                registry:
                  runtimeDir: /registry
                  signer: node-1
                  cliBackend: CACHE_ONLY
                  pollIntervalMs: 1000
                control:
                  host: 0.0.0.0
                  port: 8000
                peerSampling:
                  advertisedHost: pubsub-node-1
                  viewSize: 2
                  swapLength: 2
                  cycleIntervalMs: 2000
                  ageThreshold: 10
                  proofFanout: 2
                  randomSeed: 1
                navigation:
                  capacity: 2
                  routingBase: 2
                  cycleIntervalMs: 2000
                  staleAfterMs: 20000
                  subscriptionsPath: /data/identity/subscriptions.json
                dissemination:
                  randomLinkCount: 1
                  cycleIntervalMs: 2000
                  staleAfterMs: 20000
                  randomSeed: 2
                persistence:
                  membershipPath: /registry/servers.json
                  deliveryStatePath: /data/identity/recovery-state.json
                  connectionTimeoutMs: 1000
                  requestTimeoutMs: 3000
                  retries: 1
                  recoveryConcurrency: 8
                """);

        assertEquals("node-1", config.node().name);
        assertTrue(config.peers().isEmpty());
        assertEquals(8000, config.control().port);
        assertNotNull(config.sampling());
        assertEquals("pubsub-node-1", config.sampling().advertisedHost);
        assertEquals(2, config.sampling().viewSize);
        assertEquals(2, config.sampling().proofFanout);
        assertNotNull(config.navigation());
    }

    @Test
    void rejectsMissingValuesInsteadOfApplyingJavaDefaults() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> load("""
                node:
                  name: node-1
                  listenHost: 0.0.0.0
                  listenPort: 7000
                  identityPath: /data/identity
                peers: []
                transport:
                  pingIntervalMs: 5000
                  pingTimeoutMs: 3000
                  reconnectInitialMs: 1000
                  reconnectMaxMs: 10000
                control:
                  host: 0.0.0.0
                """));

        assertEquals("control.port must be between 1 and 65535", exception.getMessage());
    }

    @Test
    void rejectsUnknownProperties() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> load("""
                node:
                  name: node-1
                  listenHost: 0.0.0.0
                  listenPort: 7000
                  identityPath: /data/identity
                  unexpected: true
                peers: []
                transport:
                  pingIntervalMs: 5000
                  pingTimeoutMs: 3000
                  reconnectInitialMs: 1000
                  reconnectMaxMs: 10000
                control:
                  host: 0.0.0.0
                  port: 8000
                """));

        assertTrue(exception.getCause().getMessage().contains("unexpected"));
    }

    @Test
    void appliesArchitecturalValidationAfterDeserialization() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> load("""
                node:
                  name: node-1
                  listenHost: 0.0.0.0
                  listenPort: 7000
                  identityPath: /data/identity
                peers: []
                transport:
                  pingIntervalMs: 5000
                  pingTimeoutMs: 3000
                  reconnectInitialMs: 1000
                  reconnectMaxMs: 10000
                control:
                  host: 0.0.0.0
                  port: 8000
                dissemination:
                  randomLinkCount: 1
                  cycleIntervalMs: 2000
                  staleAfterMs: 20000
                  randomSeed: 1
                """));

        assertEquals("Pub-sub config must define registry, peerSampling, navigation, dissemination, and persistence sections",
                exception.getMessage());
    }

    @Test
    void rejectsPeerSamplingWithoutNavigation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> load("""
                node: {name: node-1, listenHost: 0.0.0.0, listenPort: 7000, identityPath: /data/identity}
                peers: []
                transport: {pingIntervalMs: 5000, pingTimeoutMs: 3000, reconnectInitialMs: 1000, reconnectMaxMs: 10000}
                control: {host: 0.0.0.0, port: 8000}
                peerSampling: {advertisedHost: pubsub-node-1, viewSize: 2, swapLength: 2, cycleIntervalMs: 2000, ageThreshold: 10, proofFanout: 2, randomSeed: 1}
                """));

        assertEquals("Pub-sub config must define registry, peerSampling, navigation, dissemination, and persistence sections",
                exception.getMessage());
    }

    @Test
    void rejectsNavigationWithoutPeerSampling() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> load("""
                node: {name: node-1, listenHost: 0.0.0.0, listenPort: 7000, identityPath: /data/identity}
                peers: []
                transport: {pingIntervalMs: 5000, pingTimeoutMs: 3000, reconnectInitialMs: 1000, reconnectMaxMs: 10000}
                control: {host: 0.0.0.0, port: 8000}
                navigation: {capacity: 2, routingBase: 2, cycleIntervalMs: 2000, staleAfterMs: 20000, subscriptionsPath: /data/identity/subscriptions.json}
                """));

        assertEquals("Pub-sub config must define registry, peerSampling, navigation, dissemination, and persistence sections",
                exception.getMessage());
    }

    @Test
    void rejectsOutOfRangeNavigationValuesAtLoadTime() {
        IllegalArgumentException capacityException = assertThrows(IllegalArgumentException.class, () -> load("""
                node: {name: node-1, listenHost: 0.0.0.0, listenPort: 7000, identityPath: /data/identity}
                peers: []
                transport: {pingIntervalMs: 5000, pingTimeoutMs: 3000, reconnectInitialMs: 1000, reconnectMaxMs: 10000}
                registry: {runtimeDir: /registry, signer: node-1, cliBackend: CACHE_ONLY, pollIntervalMs: 1000}
                control: {host: 0.0.0.0, port: 8000}
                peerSampling: {advertisedHost: pubsub-node-1, viewSize: 2, swapLength: 2, cycleIntervalMs: 2000, ageThreshold: 10, proofFanout: 2, randomSeed: 1}
                navigation: {capacity: 257, routingBase: 2, cycleIntervalMs: 2000, staleAfterMs: 20000, subscriptionsPath: /data/identity/subscriptions.json}
                dissemination: {randomLinkCount: 1, cycleIntervalMs: 2000, staleAfterMs: 20000, randomSeed: 2}
                persistence: {membershipPath: /registry/servers.json, deliveryStatePath: /data/identity/recovery-state.json, connectionTimeoutMs: 1000, requestTimeoutMs: 3000, retries: 1, recoveryConcurrency: 8}
                """));

        assertEquals("navigation.capacity must be between 1 and 256", capacityException.getMessage());

        IllegalArgumentException routingBaseException = assertThrows(IllegalArgumentException.class, () -> load("""
                node: {name: node-1, listenHost: 0.0.0.0, listenPort: 7000, identityPath: /data/identity}
                peers: []
                transport: {pingIntervalMs: 5000, pingTimeoutMs: 3000, reconnectInitialMs: 1000, reconnectMaxMs: 10000}
                registry: {runtimeDir: /registry, signer: node-1, cliBackend: CACHE_ONLY, pollIntervalMs: 1000}
                control: {host: 0.0.0.0, port: 8000}
                peerSampling: {advertisedHost: pubsub-node-1, viewSize: 2, swapLength: 2, cycleIntervalMs: 2000, ageThreshold: 10, proofFanout: 2, randomSeed: 1}
                navigation: {capacity: 2, routingBase: 1, cycleIntervalMs: 2000, staleAfterMs: 20000, subscriptionsPath: /data/identity/subscriptions.json}
                dissemination: {randomLinkCount: 1, cycleIntervalMs: 2000, staleAfterMs: 20000, randomSeed: 2}
                persistence: {membershipPath: /registry/servers.json, deliveryStatePath: /data/identity/recovery-state.json, connectionTimeoutMs: 1000, requestTimeoutMs: 3000, retries: 1, recoveryConcurrency: 8}
                """));

        assertEquals("navigation.routingBase must be between 2 and 64", routingBaseException.getMessage());
    }

    @Test
    void rejectsInvalidPeerSamplingValuesAtLoadTime() {
        IllegalArgumentException viewSizeException = assertThrows(
                IllegalArgumentException.class, () -> loadSamplingConfig(257, 2, 2000, 10));
        assertEquals("peerSampling.viewSize must be between 1 and 256", viewSizeException.getMessage());

        IllegalArgumentException swapLengthException = assertThrows(
                IllegalArgumentException.class, () -> loadSamplingConfig(2, 3, 2000, 10));
        assertEquals("peerSampling.swapLength must be between 1 and 2", swapLengthException.getMessage());

        IllegalArgumentException ageThresholdException = assertThrows(
                IllegalArgumentException.class, () -> loadSamplingConfig(2, 2, 2000, 10001));
        assertEquals("peerSampling.ageThreshold must be between 0 and 10000", ageThresholdException.getMessage());
    }

    @Test
    void acceptsZeroPeerSamplingAgeThreshold() throws IOException {
        NodeConfig config = loadSamplingConfig(2, 2, 2000, 0);

        assertEquals(0, config.sampling().ageThreshold);
    }

    @Test
    void checkedInPubSubConfigsSatisfyTheCompleteRoleContract() throws IOException {
        Path configRoot = Path.of("..", "..", "ops", "config").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(configRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("node-\\d+\\.yaml"))
                    .toList()) {
                NodeConfig.load(file);
            }
        }
    }

    private NodeConfig loadSamplingConfig(int viewSize, int swapLength, long cycleIntervalMs,
                                          int ageThreshold) throws IOException {
        return load("""
                node: {name: node-1, listenHost: 0.0.0.0, listenPort: 7000, identityPath: /data/identity}
                peers: []
                transport: {pingIntervalMs: 5000, pingTimeoutMs: 3000, reconnectInitialMs: 1000, reconnectMaxMs: 10000}
                registry: {runtimeDir: /registry, signer: node-1, cliBackend: CACHE_ONLY, pollIntervalMs: 1000}
                control: {host: 0.0.0.0, port: 8000}
                peerSampling: {advertisedHost: pubsub-node-1, viewSize: %d, swapLength: %d, cycleIntervalMs: %d, ageThreshold: %d, proofFanout: 2, randomSeed: 1}
                navigation: {capacity: 2, routingBase: 2, cycleIntervalMs: 2000, staleAfterMs: 20000, subscriptionsPath: /data/identity/subscriptions.json}
                dissemination: {randomLinkCount: 1, cycleIntervalMs: 2000, staleAfterMs: 20000, randomSeed: 2}
                persistence: {membershipPath: /registry/servers.json, deliveryStatePath: /data/identity/recovery-state.json, connectionTimeoutMs: 1000, requestTimeoutMs: 3000, retries: 1, recoveryConcurrency: 8}
                """.formatted(viewSize, swapLength, cycleIntervalMs, ageThreshold));
    }

    private NodeConfig load(String yaml) throws IOException {
        Path path = tempDir.resolve("node.yaml");
        Files.writeString(path, yaml);
        return NodeConfig.load(path);
    }
}
