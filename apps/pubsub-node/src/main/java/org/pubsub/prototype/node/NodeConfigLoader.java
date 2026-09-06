package org.pubsub.prototype.node;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class NodeConfigLoader {
    private NodeConfigLoader() {
    }

    static NodeConfig load(Path path) {
        Yaml yaml = new Yaml();
        try (InputStream input = Files.newInputStream(path)) {
            Map<String, Object> root = yaml.load(input);
            NodeConfig config = fromMap(root);
            validate(config);
            return config;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read config " + path, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static NodeConfig fromMap(Map<String, Object> root) {
        if (root == null) {
            return null;
        }

        NodeConfig.NodeSection node = new NodeConfig.NodeSection();
        Map<String, Object> nodeMap = section(root, NodeConfigField.NODE);
        node.name = stringValue(nodeMap, NodeConfigField.NAME);
        node.listenHost = stringValue(nodeMap, NodeConfigField.LISTEN_HOST);
        node.listenPort = intValue(nodeMap, NodeConfigField.LISTEN_PORT);
        node.identityPath = stringValue(nodeMap, NodeConfigField.IDENTITY_PATH);

        List<NodeConfig.PeerSection> peers = ((List<Map<String, Object>>) root.getOrDefault(NodeConfigField.PEERS.yamlName(), List.of()))
                .stream()
                .map(peerMap -> {
                    NodeConfig.PeerSection peer = new NodeConfig.PeerSection();
                    peer.host = stringValue(peerMap, NodeConfigField.HOST);
                    peer.port = intValue(peerMap, NodeConfigField.PORT);
                    return peer;
                })
                .toList();

        NodeConfig.TransportSection transport = new NodeConfig.TransportSection();
        Map<String, Object> transportMap = section(root, NodeConfigField.TRANSPORT);
        transport.pingIntervalMs = longValue(transportMap, NodeConfigField.PING_INTERVAL_MS);
        transport.pingTimeoutMs = longValue(transportMap, NodeConfigField.PING_TIMEOUT_MS);
        transport.reconnectInitialMs = longValue(transportMap, NodeConfigField.RECONNECT_INITIAL_MS);
        transport.reconnectMaxMs = longValue(transportMap, NodeConfigField.RECONNECT_MAX_MS);

        NodeConfig.RegistrySection registry = null;
        Map<String, Object> registryMap = section(root, NodeConfigField.REGISTRY);
        if (!registryMap.isEmpty()) {
            registry = new NodeConfig.RegistrySection();
            registry.enabled = booleanValue(registryMap, NodeConfigField.ENABLED, false);
            registry.runtimeDir = stringValue(registryMap, NodeConfigField.RUNTIME_DIR);
            registry.signer = stringValue(registryMap, NodeConfigField.SIGNER);
            registry.pollIntervalMs = longValue(registryMap, NodeConfigField.POLL_INTERVAL_MS, 2000);
        }

        NodeConfig.ControlSection control = null;
        Map<String, Object> controlMap = section(root, NodeConfigField.CONTROL);
        if (!controlMap.isEmpty()) {
            control = new NodeConfig.ControlSection();
            control.host = stringValue(controlMap, NodeConfigField.HOST);
            Object port = controlMap.get(NodeConfigField.PORT.yamlName());
            control.port = port == null ? 8000 : intValue(controlMap, NodeConfigField.PORT);
        }

        NodeConfig.SamplingSection sampling = null;
        if (root.get("peerSampling") instanceof Map<?, ?> map) {
            sampling = new NodeConfig.SamplingSection();
            sampling.advertisedHost = (String) map.get("advertisedHost");
            if (map.get("viewSize") instanceof Number n) sampling.viewSize = n.intValue();
            if (map.get("swapLength") instanceof Number n) sampling.swapLength = n.intValue();
            if (map.get("cycleIntervalMs") instanceof Number n) sampling.cycleIntervalMs = n.longValue();
            if (map.get("ageThreshold") instanceof Number n) sampling.ageThreshold = n.intValue();
            if (map.get("randomSeed") instanceof Number n) sampling.randomSeed = n.longValue();
        }
        NodeConfig.NavigationSection navigation = null;
        if (root.get("navigation") instanceof Map<?, ?> map) {
            navigation = new NodeConfig.NavigationSection();
            if (map.get("capacity") instanceof Number n) navigation.capacity = n.intValue();
            if (map.get("routingBase") instanceof Number n) navigation.routingBase = n.intValue();
            if (map.get("cycleIntervalMs") instanceof Number n) navigation.cycleIntervalMs = n.longValue();
            if (map.get("staleAfterMs") instanceof Number n) navigation.staleAfterMs = n.longValue();
            if (map.get("subscriptionsPath") instanceof String s) navigation.subscriptionsPath = s;
        }
        NodeConfig.DisseminationSection dissemination = null;
        if (root.get("dissemination") instanceof Map<?, ?> map) {
            dissemination = new NodeConfig.DisseminationSection();
            if (map.get("randomLinkCount") instanceof Number n) dissemination.randomLinkCount = n.intValue();
            if (map.get("cycleIntervalMs") instanceof Number n) dissemination.cycleIntervalMs = n.longValue();
            if (map.get("staleAfterMs") instanceof Number n) dissemination.staleAfterMs = n.longValue();
            if (map.get("randomSeed") instanceof Number n) dissemination.randomSeed = n.longValue();
        }
        return new NodeConfig(node, peers, transport, registry, control, sampling, navigation, dissemination);
    }

    private static Map<String, Object> section(Map<String, Object> root, NodeConfigField name) {
        Object value = root.get(name.yamlName());
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private static String stringValue(Map<String, Object> map, NodeConfigField name) {
        Object value = map.get(name.yamlName());
        return value == null ? null : value.toString();
    }

    private static int intValue(Map<String, Object> map, NodeConfigField name) {
        Object value = map.get(name.yamlName());
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static long longValue(Map<String, Object> map, NodeConfigField name) {
        Object value = map.get(name.yamlName());
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static long longValue(Map<String, Object> map, NodeConfigField name, long defaultValue) {
        Object value = map.get(name.yamlName());
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static boolean booleanValue(Map<String, Object> map, NodeConfigField name, boolean defaultValue) {
        Object value = map.get(name.yamlName());
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private static void validate(NodeConfig config) {
        if (config == null || config.node() == null || config.transport() == null) {
            throw new IllegalArgumentException("Config must define node and transport sections");
        }
        if (config.node().name == null || config.node().name.isBlank()) {
            throw new IllegalArgumentException(fieldPath(NodeConfigField.NODE, NodeConfigField.NAME) + " is required");
        }
        if (config.node().identityPath == null || config.node().identityPath.isBlank()) {
            throw new IllegalArgumentException(fieldPath(NodeConfigField.NODE, NodeConfigField.IDENTITY_PATH) + " is required");
        }
        if (config.registryEnabled()) {
            if (config.registry().runtimeDir == null || config.registry().runtimeDir.isBlank()) {
                throw new IllegalArgumentException(fieldPath(NodeConfigField.REGISTRY, NodeConfigField.RUNTIME_DIR)
                        + " is required when registry is enabled");
            }
            if (config.registry().signer == null || config.registry().signer.isBlank()) {
                throw new IllegalArgumentException(fieldPath(NodeConfigField.REGISTRY, NodeConfigField.SIGNER)
                        + " is required when registry is enabled");
            }
            if (config.registry().pollIntervalMs <= 0) {
                throw new IllegalArgumentException(fieldPath(NodeConfigField.REGISTRY, NodeConfigField.POLL_INTERVAL_MS)
                        + " must be greater than zero");
            }
        }
        if (config.dissemination() != null) {
            if (config.navigation() == null || config.sampling() == null) {
                throw new IllegalArgumentException("dissemination requires navigation and peerSampling");
            }
            if (config.dissemination().randomLinkCount < 1
                    || config.dissemination().cycleIntervalMs < 1
                    || config.dissemination().staleAfterMs < 1) {
                throw new IllegalArgumentException("Invalid dissemination configuration");
            }
        }
    }

    private static String fieldPath(NodeConfigField section, NodeConfigField field) {
        return section.yamlName() + "." + field.yamlName();
    }

    private enum NodeConfigField {
        NODE("node"),
        PEERS("peers"),
        TRANSPORT("transport"),
        REGISTRY("registry"),
        CONTROL("control"),
        NAME("name"),
        LISTEN_HOST("listenHost"),
        LISTEN_PORT("listenPort"),
        IDENTITY_PATH("identityPath"),
        HOST("host"),
        PORT("port"),
        PING_INTERVAL_MS("pingIntervalMs"),
        PING_TIMEOUT_MS("pingTimeoutMs"),
        RECONNECT_INITIAL_MS("reconnectInitialMs"),
        RECONNECT_MAX_MS("reconnectMaxMs"),
        ENABLED("enabled"),
        RUNTIME_DIR("runtimeDir"),
        SIGNER("signer"),
        POLL_INTERVAL_MS("pollIntervalMs");

        private final String yamlName;

        NodeConfigField(String yamlName) {
            this.yamlName = yamlName;
        }

        String yamlName() {
            return yamlName;
        }
    }
}
