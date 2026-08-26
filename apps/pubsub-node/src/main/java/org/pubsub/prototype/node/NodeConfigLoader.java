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
        Map<String, Object> nodeMap = section(root, "node");
        node.name = stringValue(nodeMap, "name");
        node.listenHost = stringValue(nodeMap, "listenHost");
        node.listenPort = intValue(nodeMap, "listenPort");
        node.identityPath = stringValue(nodeMap, "identityPath");

        List<NodeConfig.PeerSection> peers = ((List<Map<String, Object>>) root.getOrDefault("peers", List.of()))
                .stream()
                .map(peerMap -> {
                    NodeConfig.PeerSection peer = new NodeConfig.PeerSection();
                    peer.host = stringValue(peerMap, "host");
                    peer.port = intValue(peerMap, "port");
                    return peer;
                })
                .toList();

        NodeConfig.TransportSection transport = new NodeConfig.TransportSection();
        Map<String, Object> transportMap = section(root, "transport");
        transport.pingIntervalMs = longValue(transportMap, "pingIntervalMs");
        transport.pingTimeoutMs = longValue(transportMap, "pingTimeoutMs");
        transport.reconnectInitialMs = longValue(transportMap, "reconnectInitialMs");
        transport.reconnectMaxMs = longValue(transportMap, "reconnectMaxMs");

        return new NodeConfig(node, peers, transport);
    }

    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Object value = root.get(name);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private static String stringValue(Map<String, Object> map, String name) {
        Object value = map.get(name);
        return value == null ? null : value.toString();
    }

    private static int intValue(Map<String, Object> map, String name) {
        Object value = map.get(name);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static long longValue(Map<String, Object> map, String name) {
        Object value = map.get(name);
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static void validate(NodeConfig config) {
        if (config == null || config.node() == null || config.transport() == null) {
            throw new IllegalArgumentException("Config must define node and transport sections");
        }
        if (config.node().name == null || config.node().name.isBlank()) {
            throw new IllegalArgumentException("node.name is required");
        }
        if (config.node().identityPath == null || config.node().identityPath.isBlank()) {
            throw new IllegalArgumentException("node.identityPath is required");
        }
    }
}
