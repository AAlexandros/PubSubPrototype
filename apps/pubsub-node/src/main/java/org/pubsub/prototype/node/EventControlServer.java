package org.pubsub.prototype.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.pubsub.prototype.event.EventEnvelope;
import org.pubsub.prototype.registry.TopicId;

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
