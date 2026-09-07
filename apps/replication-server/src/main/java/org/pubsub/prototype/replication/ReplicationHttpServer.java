package org.pubsub.prototype.replication;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.persistence.PersistenceHex;
import org.pubsub.prototype.persistence.PublisherProgress;
import org.pubsub.prototype.persistence.ReplicationServer;
import org.pubsub.prototype.persistence.StoredEvent;
import org.pubsub.prototype.persistence.core.PersistenceJson;
import org.pubsub.prototype.persistence.core.ReplicationMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

final class ReplicationHttpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationHttpServer.class);
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private final HttpServer server;
    private final ReplicationService service;
    private final ReplicationMembership membership;
    private final ReplicationServer self;

    ReplicationHttpServer(String host, int port, ReplicationServer self, ReplicationService service,
                          ReplicationMembership membership) throws IOException {
        this.self = self;
        this.service = service;
        this.membership = membership;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/v1/events", this::events);
        server.createContext("/v1/topics", this::topics);
        server.createContext("/v1/replicas/events", this::replicaEvents);
        server.createContext("/v1/replicas/topics", this::replicaTopics);
        server.createContext("/v1/health", this::health);
        server.setExecutor(Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "replication-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    void start() {
        server.start();
    }

    private void events(HttpExchange exchange) throws IOException {
        String suffix = suffix(exchange, "/v1/events");
        try {
            if ("POST".equals(exchange.getRequestMethod()) && suffix.isEmpty()) {
                StoredEvent event = service.persist(read(exchange, EventEnvelope.class));
                respond(exchange, 201, event);
            } else if ("GET".equals(exchange.getRequestMethod()) && !suffix.isEmpty()) {
                String key = PersistenceHex.require256(suffix, "eventKey");
                var found = service.lookup(key);
                if (found.isPresent()) respond(exchange, 200, found.orElseThrow());
                else respond(exchange, 404, Map.of("error", "event_not_found", "eventKey", key));
            } else {
                respond(exchange, 405, Map.of("error", "method_not_allowed"));
            }
        } catch (IllegalArgumentException ex) {
            if ("POST".equals(exchange.getRequestMethod())) {
                LOG.info("EVENT_PERSIST_FAILED serverId={} reason={}", self.serverId(), safeMessage(ex));
            }
            respond(exchange, 400, Map.of("error", safeMessage(ex)));
        } catch (RuntimeException ex) {
            LOG.info("EVENT_PERSIST_FAILED serverId={} reason={}", self.serverId(), safeMessage(ex));
            respond(exchange, 503, Map.of("error", safeMessage(ex)));
        }
    }

    private void replicaEvents(HttpExchange exchange) throws IOException {
        String suffix = suffix(exchange, "/v1/replicas/events");
        try {
            if ("POST".equals(exchange.getRequestMethod()) && suffix.isEmpty()) {
                StoredEvent event = read(exchange, StoredEvent.class);
                service.storeReplica(event);
                respond(exchange, 201, event);
            } else if ("GET".equals(exchange.getRequestMethod()) && !suffix.isEmpty()) {
                String key = PersistenceHex.require256(suffix, "eventKey");
                var found = service.lookupLocal(key);
                if (found.isPresent()) respond(exchange, 200, found.orElseThrow());
                else respond(exchange, 404, Map.of("error", "event_not_found", "eventKey", key));
            } else respond(exchange, 405, Map.of("error", "method_not_allowed"));
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of("error", safeMessage(ex)));
        }
    }

    private void topics(HttpExchange exchange) throws IOException {
        String[] parts = suffix(exchange, "/v1/topics").split("/");
        try {
            if (!"GET".equals(exchange.getRequestMethod()) || parts.length != 2 || !"publishers".equals(parts[1])) {
                respond(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            long sinceTimestamp = queryLong(exchange, "sinceTimestamp", 0);
            respond(exchange, 200, service.publisherProgress(
                    PersistenceHex.require256(parts[0], "topicId"), sinceTimestamp));
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of("error", safeMessage(ex)));
        }
    }

    private void replicaTopics(HttpExchange exchange) throws IOException {
        String[] parts = suffix(exchange, "/v1/replicas/topics").split("/");
        try {
            if (parts.length != 2 || !"publishers".equals(parts[1])) {
                respond(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            String topicId = PersistenceHex.require256(parts[0], "topicId");
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, service.localPublisherProgress(topicId));
            } else if ("POST".equals(exchange.getRequestMethod())) {
                PublisherProgress progress = read(exchange, PublisherProgress.class);
                service.storeProgressReplica(topicId, progress);
                respond(exchange, 200, progress);
            } else respond(exchange, 405, Map.of("error", "method_not_allowed"));
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of("error", safeMessage(ex)));
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("serverId", self.serverId());
        result.put("membership", membership.activeServers());
        result.put("eventKeys", service.store().eventKeys());
        respond(exchange, 200, result);
    }

    private static String suffix(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(prefix)) return "";
        if (!path.startsWith(prefix + "/")) return "";
        return path.substring(prefix.length() + 1);
    }

    private static long queryLong(HttpExchange exchange, String name, long fallback) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return fallback;
        for (String part : query.split("&")) {
            String[] pieces = part.split("=", 2);
            if (pieces[0].equals(name)) {
                long value = Long.parseLong(pieces.length == 2 ? pieces[1] : "");
                if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
                return value;
            }
        }
        return fallback;
    }

    private static <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("request payload too large");
        return PersistenceJson.MAPPER.readValue(bytes, type);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = PersistenceJson.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
