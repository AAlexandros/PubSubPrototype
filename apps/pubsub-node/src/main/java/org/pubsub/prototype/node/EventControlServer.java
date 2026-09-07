package org.pubsub.prototype.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.registry.TopicId;
import org.pubsub.prototype.sampling.PeerSamplingService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

final class EventControlServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final NodeEventService events;
    private DisseminationRuntime dissemination;
    private NodePersistenceRuntime persistence;

    EventControlServer(String host, int port, NodeEventService events) throws IOException {
        this.events = events;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/v1/events/publish", this::publish);
        this.server.createContext("/v1/events/inject", this::inject);
        this.server.createContext("/v1/events/publisher-key-id", this::publisherKeyId);
        this.server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-control");
            thread.setDaemon(true);
            return thread;
        }));
    }

    void addSampling(PeerSamplingService sampling, String nodeId, int capacity) {
        server.createContext("/v1/peer-sampling/view", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            respond(exchange, 200, Map.of("nodeId", nodeId, "capacity", capacity, "view", sampling.view()));
        });
    }

    void addNavigation(NavigationRuntime navigation, String nodeId) {
        server.createContext("/v1/navigation/view", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            var engine = navigation.engine();
            respond(exchange, 200, Map.of(
                    "nodeId", nodeId,
                    "topicOrdering", engine.ordering().topicIds(),
                    "subscriptions", engine.subscriptions(),
                    "fingerTopics", engine.fingerTopics(),
                    "view", engine.view()
            ));
        });
        server.createContext("/v1/subscriptions", exchange -> {
            String prefix = "/v1/subscriptions";
            String path = exchange.getRequestURI().getPath();
            String topicId = path.length() > prefix.length() ? path.substring(prefix.length() + 1) : "";
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        if (!topicId.isEmpty()) {
                            respond(exchange, 405, Map.of("error", "method_not_allowed"));
                            return;
                        }
                        respond(exchange, 200, Map.of("nodeId", nodeId, "subscriptions", navigation.engine().subscriptions()));
                    }
                    case "POST" -> {
                        if (topicId.isEmpty()) {
                            respond(exchange, 400, Map.of("error", "topicId is required"));
                            return;
                        }
                        navigation.subscribe(new TopicId(topicId).value());
                        if (dissemination != null) dissemination.subscribe(topicId);
                        respond(exchange, 200, Map.of("topicId", topicId, "subscribed", true));
                    }
                    case "DELETE" -> {
                        if (topicId.isEmpty()) {
                            respond(exchange, 400, Map.of("error", "topicId is required"));
                            return;
                        }
                        navigation.unsubscribe(new TopicId(topicId).value());
                        if (dissemination != null) dissemination.unsubscribe(topicId);
                        respond(exchange, 200, Map.of("topicId", topicId, "subscribed", false));
                    }
                    default -> respond(exchange, 405, Map.of("error", "method_not_allowed"));
                }
            } catch (RuntimeException ex) {
                respond(exchange, 400, Map.of("error", ex.getMessage()));
            }
        });
    }

    void addDissemination(DisseminationRuntime dissemination, String nodeId) {
        this.dissemination = dissemination;
        server.createContext("/v1/dissemination/view", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            String prefix = "/v1/dissemination/view";
            String path = exchange.getRequestURI().getPath();
            String topicId = path.length() > prefix.length() ? path.substring(prefix.length() + 1) : "";
            if (topicId.isEmpty()) {
                respond(exchange, 200, Map.of("nodeId", nodeId, "views", dissemination.engine().views()));
                return;
            }
            try {
                String validTopicId = new TopicId(topicId).value();
                var view = dissemination.engine().view(validTopicId);
                if (view.isEmpty()) {
                    respond(exchange, 404, Map.of("error", "dissemination_view_not_found", "topicId", validTopicId));
                } else {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("nodeId", nodeId);
                    response.put("topicId", view.get().topicId());
                    response.put("predecessor", view.get().predecessor());
                    response.put("successor", view.get().successor());
                    response.put("randomPeers", view.get().randomPeers());
                    respond(exchange, 200, response);
                }
            } catch (RuntimeException ex) {
                respond(exchange, 400, Map.of("error", ex.getMessage()));
            }
        });
    }

    void addPersistence(NodePersistenceRuntime persistence) {
        this.persistence = persistence;
        server.createContext("/v1/events/recover", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            String prefix = "/v1/events/recover";
            String path = exchange.getRequestURI().getPath();
            String topicId = path.length() > prefix.length() ? path.substring(prefix.length() + 1) : "";
            try {
                if (topicId.isEmpty()) throw new IllegalArgumentException("topicId is required");
                respond(exchange, 200, persistence.recover(new TopicId(topicId).value()));
            } catch (RuntimeException ex) {
                respond(exchange, 400, Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        });
        server.createContext("/v1/events/recovery-state", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            respond(exchange, 200, persistence.deliveryState());
        });
    }

    void start() {
        server.start();
    }

    private void publish(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        try {
            PublishRequest request = MAPPER.readValue(exchange.getRequestBody(), PublishRequest.class);
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            EventEnvelope event = events.publish(
                    new TopicId(request.topicId),
                    Base64.getDecoder().decode(request.payload),
                    Boolean.parseBoolean(query.getOrDefault("forceBroadcast", "false")),
                    Boolean.parseBoolean(query.getOrDefault("tamperSignature", "false"))
            );
            respond(exchange, 200, new PublishResponse(event.eventId(), event.sequenceNumber(), event));
        } catch (RuntimeException ex) {
            respond(exchange, 400, Map.of("error", ex.getMessage()));
        }
    }

    private void publisherKeyId(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        respond(exchange, 200, Map.of("publisherKeyId", events.publisherKeyId()));
    }

    private void inject(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        try {
            EventEnvelope event = MAPPER.readValue(exchange.getRequestBody(), EventEnvelope.class);
            EventEnvelope injected = events.inject(event);
            respond(exchange, 200, new PublishResponse(injected.eventId(), injected.sequenceNumber(), injected));
        } catch (RuntimeException ex) {
            respond(exchange, 400, Map.of("error", ex.getMessage()));
        }
    }

    private static Map<String, String> query(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            String[] pieces = part.split("=", 2);
            result.put(pieces[0], pieces.length == 2 ? pieces[1] : "true");
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static final class PublishRequest {
        public String topicId;
        public String payload;
    }

    private record PublishResponse(String eventId, long sequenceNumber, EventEnvelope envelope) {
    }
}
